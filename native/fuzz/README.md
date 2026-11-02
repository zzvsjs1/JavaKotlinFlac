# Native sanitizer and fuzz support

The native fuzz targets use Clang's libFuzzer together with AddressSanitizer
and UndefinedBehaviourSanitizer. The Gradle entry points support Linux x86_64
and are not packaged in the library.

The fuzz-only static dependencies use
`-fsanitize=fuzzer-no-link,address,undefined`: libFLAC is written to
`build/flac-native-sanitizer/lib/libFLAC.a`, and libogg to
`build/ogg-native-fuzz/lib/libogg.a`. The shared libFLAC used by JVM sanitizer
tests instead links `build/ogg-native-sanitizer/lib/libogg.a`, which carries
ASan/UBSan instrumentation only. This keeps libFuzzer coverage runtime symbols
out of the shared JVM runtime.

All three libogg builds consume the same checksum-verified source with the
ordered patches in `native/patches`. The empty-sync buffer patch avoids null
pointer arithmetic when page scanning starts before the first input buffer is
allocated; sanitizer checks remain enabled.

Fuzzer configuration inspects the actual static libFLAC and libogg archives
with `nm -u` and rejects dependencies without `__sanitizer_cov_` references.
The same read-only check can inspect existing archives directly:

```bash
cmake -DJFLAC_FLAC_STATIC_LIBRARY=/path/to/libFLAC.a \
  -DJFLAC_OGG_STATIC_LIBRARY=/path/to/libogg.a \
  -P native/cmake/VerifyFuzzCoverage.cmake
```

`nativeSanitizerJvmTest` runs the normal non-external JVM suite with
ASan/UBSan-instrumented libFLAC and JNI libraries on an isolated classpath.
Leak detection is disabled only for this JVM worker because process-wide JVM
allocations are not useful libFLAC leak signals; invalid memory access and
undefined behaviour still fail the task. The standalone fuzz processes keep
leak detection enabled.

`jflac-decoder-fuzz` exercises Native FLAC and Ogg FLAC through both sequential
and seekable in-memory transports. It parses metadata, performs bounded seeks,
and decodes at most 1,048,576 sample frames per variant.

`jflac-metadata-fuzz` reads Native FLAC and Ogg FLAC metadata chains through the
callback API. It traverses and clones at most 512 blocks, checks structured
metadata, and exercises in-memory padding operations without writing a file.

Both targets reject inputs larger than 1 MiB. Malformed or truncated media is
an expected parser result and returns normally. A sanitizer report, process
crash, ten-second per-input timeout, or 2 GiB RSS limit is a hard failure in the
Gradle smoke task.

## Corpus

The reviewable seed sources live in `native/fuzz/seeds`:

- `native-metadata.hex` is a valid Native FLAC metadata chain without audio.
- `native-truncated-block.hex` declares a deliberately truncated metadata block.
- `ogg-flac.b64` is a valid single-link Ogg FLAC file containing audio.

`prepareNativeFuzzCorpus` strips comments from hexadecimal sources, decodes the
Base64 source, and writes raw binary seeds beneath `build/native-fuzz-corpus`.
Every seed is given to both targets so container-signature mutations can cross
between decoder, metadata, and transport paths.

## Bounded smoke run

Run the complete sanitizer JVM suite and deterministic fuzz smoke budget on
Linux x86_64:

```bash
bash ./gradlew nativeSanitizerSmoke --no-daemon --stacktrace
```

Each fuzz target receives 512 libFuzzer runs with the fixed random seed `1`.
Crash artefacts are retained beneath `build/native-fuzz-artifacts` for
reproduction. The aggregate executes
`nativeSanitizerJvmTest`, `decoderFuzzSmoke`, and `metadataFuzzSmoke` in that
order.

For a longer local session, first build the targets and prepare the corpus:

```bash
bash ./gradlew buildNativeFuzzers prepareNativeFuzzCorpus --no-daemon
build/native-sanitizer/fuzz/jflac-decoder-fuzz build/native-fuzz-corpus/decoder -max_len=1048576
build/native-sanitizer/fuzz/jflac-metadata-fuzz build/native-fuzz-corpus/metadata -max_len=1048576
```

libFuzzer writes a reproducer when it finds a hard failure. Re-run that file as
the only positional input to obtain the sanitizer stack trace before reducing
or adding it to the seed corpus.

## Native formatting

The repository's `.clang-format-version` pins the formatter used locally and
by the dedicated CI gate. Install that exact version of `clang-format`, then
run the read-only check (JDK 21 or newer is required):

```bash
python -m pip install "clang-format==$(tr -d '\r\n' < .clang-format-version)"
bash ./gradlew verifyNativeFormat --no-daemon
```

On Windows PowerShell, use an installed formatter with the pinned version:

```powershell
.\gradlew.bat verifyNativeFormat --no-daemon '-Pjflac.clangFormatExecutable=C:/path/to/clang-format.exe'
```

`JFLAC_CLANG_FORMAT` can also select the executable. The task checks tracked
native C/H files using `.clang-format`; it never rewrites them. Ordinary
builds do not require a formatter.
