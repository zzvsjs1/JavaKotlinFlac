# JavaKotlinFlac

Java and Kotlin JNI wrapper for libFLAC. It provides file-based metadata APIs
and file, sequential-stream, and seekable-channel decode and encode workflows.
Host builds bundle one matching native runtime; release CI assembles all
supported runtimes into one cross-platform artefact.

## Status

- JVM target: Java 21
- Native build and loader targets: Windows x64, Linux x64, macOS x64, and
  macOS Arm64
- Bundled native libraries: the platform libFLAC shared library and JNI shim
- Windows MSVC runtime: statically linked into the bundled Windows DLLs
- FLAC source: official FLAC 1.5.0 archive, SHA-256 checked during build
- libogg source: official libogg 1.3.6 archive, SHA-256 checked during build
- Ogg FLAC: single-link decode, encode, and metadata reading are supported;
  the direct decoder can opt into whole-stream chained Ogg decode, while
  existing-file Ogg metadata editing is not supported
- CI: builds and tests each native target, assembles one universal JAR, then
  runs the same JAR through a consumer smoke test on all four targets

## Requirements

- JDK 21 to run the current Gradle build
- Java 21 or newer for consumers
- A supported native build host: Windows x64, Linux x64, macOS x64, or macOS
  Arm64
- CMake, Git, `tar`, and a platform C toolchain
- Visual Studio C++ x64 tools and Ninja on Windows
- `nm` and `readelf` on Linux, or `nm` and `otool` on macOS, for native checks

## Build

```powershell
.\gradlew.bat build
```

On Linux or macOS, run the wrapper through Bash:

```bash
bash ./gradlew build
```

The build downloads FLAC 1.5.0 and libogg 1.3.6, verifies both archive
checksums, and applies jflac's ordered-metadata Vorbis vendor preservation
patch. It then builds static libogg, shared libFLAC with Ogg FLAC support, and
the platform JNI shim. Required exports and dynamic dependencies are checked
before the host-specific native files are staged into the JAR resources.

For clangd, `buildNative` also generates `compile_commands.json` at the project
root and under `build/native`.

```powershell
.\gradlew.bat buildNative
```

### Extended verification

The ordinary `test` and `build` tasks exclude external-tool, long-file, and
fuzz workloads so local feedback remains bounded. Run their dedicated tasks
when changing codec boundaries, native callbacks, or seek behaviour.

The long-file suite creates more decoded PCM than its 96 MiB test heap can
hold, streams the whole file, checks retained heap after collection, and then
performs 128 deterministic random seeks. It gates both elapsed time and the
logical bytes read through the seekable channel:

```powershell
.\gradlew.bat longFilePerformanceTest
```

On Linux x86_64 with Clang, the sanitizer aggregate runs the JVM integration
tests against ASan/UBSan-instrumented libFLAC and JNI libraries, then gives the
decoder and metadata libFuzzer targets a bounded deterministic smoke budget:

```bash
bash ./gradlew nativeSanitizerSmoke --no-daemon --stacktrace
```

See `native/fuzz/README.md` for corpus layout, individual fuzz tasks, limits,
and longer local fuzzing commands. The CI workflow runs both extended suites
on Linux; they are not part of the published runtime.

The cross-platform workflow builds the four native resource trees separately.
Its aggregate job supplies them through `jflac.nativeBundleDirectory`, verifies
that all eight shared-library entries are present without duplicates, and
creates one universal JAR. Ordinary local builds do not pretend to be
universal: they package only their current host target.

## Local Publication

This project is configured for local Maven publication only. It does not define
remote Maven repositories or signing. On any supported build host,
`publishToMavenLocal` publishes a host-specific artefact for development and
consumer smoke testing. Unsupported hosts are rejected, and any future remote
publication task is required to use the CI-assembled universal native bundle.

```powershell
.\gradlew.bat publishToMavenLocal
```

To verify the published artefact from a separate Java consumer project:

```powershell
.\gradlew.bat consumerSmokeTest
```

The smoke test resolves `org.zzvsjs:jflac` and
`org.zzvsjs:jflac-java-sound` from `mavenLocal()`, verifies that the bundled
host-platform native libraries load from the core JAR, decodes a small range from a generated
FLAC fixture under `build/consumer-smoke/`, and checks Java Sound SPI decode
and native/Ogg FLAC write round trips. It also encodes a deterministic PCM
buffer, reads and edits metadata, and decodes the output again to verify direct
jflac behaviours from the published artefacts.

Consumer dependency after local publication:

```kotlin
dependencies {
    implementation("org.zzvsjs:jflac:0.1.0-SNAPSHOT")
}
```

## Java Sound SPI

The optional `jflac-java-sound` artefact registers Java Sound service providers
for native FLAC and Ogg FLAC. It can decode files as signed PCM and encode
Java Sound PCM streams back to native FLAC or Ogg FLAC.

```kotlin
dependencies {
    implementation("org.zzvsjs:jflac-java-sound:0.1.0-SNAPSHOT")
}
```

Existing Java Sound callers can read a native FLAC or Ogg FLAC file as signed
PCM:

```java
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;

try (AudioInputStream input = AudioSystem.getAudioInputStream(new File("music.flac"))) {
    byte[] buffer = new byte[Math.max(1, input.getFormat().getFrameSize())];
    int bytesRead = input.read(buffer);
}
```

Java Sound callers can also write PCM to FLAC by selecting a jflac file type:

```java
import org.zzvsjs.jflac.sound.JflacAudioFileTypes;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.File;

byte[] pcm = new byte[44100 * 2 * 2];
AudioFormat format = new AudioFormat(
    AudioFormat.Encoding.PCM_SIGNED,
    44100f,
    16,
    2,
    4,
    44100f,
    false
);

try (AudioInputStream input = new AudioInputStream(
        new ByteArrayInputStream(pcm),
        format,
        44100
)) {
    AudioSystem.write(input, JflacAudioFileTypes.FLAC, new File("out.flac"));
}
```

The reader returns `PCM_SIGNED` little-endian bytes. A Java Sound frame
contains one sample for each channel:

```text
bytesPerSample = ceil(bitsPerSample / 8)
frameSize = bytesPerSample * channels
```

For 16-bit stereo, `bytesPerSample` is two and one frame is four bytes.

The writer accepts these Java Sound PCM formats:

| Encoding | Bits | Endian | Writer support |
| --- | --- | --- | --- |
| `PCM_SIGNED` | 8 | not applicable | Yes |
| `PCM_SIGNED` | 16, 24, 32 | little or big | Yes |
| `PCM_UNSIGNED` | 8 | not applicable | Yes |
| `PCM_UNSIGNED` | 16, 24, 32 | little or big | No |
| `PCM_FLOAT`, `ALAW`, `ULAW` | any | any | No |

| Capability | Java Sound SPI | Direct jflac API |
| --- | --- | --- |
| Decode native FLAC as PCM bytes | Yes | Yes |
| Decode Ogg FLAC as PCM bytes | Yes | Yes |
| Encode native FLAC from PCM bytes | Yes | Yes |
| Encode Ogg FLAC from PCM bytes | Yes | Yes |
| Read basic sample rate/channel/bit-depth | Yes | Yes |
| File-backed metadata properties | Yes | Yes |
| Ranged decode/reusable seek sessions | No | Yes |
| Direct encoder options | No | Yes |
| Existing-file metadata editing | No | Yes |
| Ordered metadata block workflows | Via format properties | Yes |

File-backed `AudioSystem.getAudioFileFormat(File)` and
`AudioSystem.getAudioInputStream(File)` calls attach richer properties for
STREAMINFO, Vorbis comments, pictures, APPLICATION blocks, SEEKTABLE blocks,
CUESHEET blocks, PADDING blocks, unknown blocks, and the ordered `flac.blocks`
list. Stream and URL format probes only expose STREAMINFO-derived properties
because those sources are read sequentially and are not suitable for full
metadata-chain inspection.

For Java Sound metadata preservation during encode, pass metadata through
`AudioInputStream.getFormat().properties()`. The grouped property keys are
useful for typed metadata, while ordered `flac.blocks` preserves the supplied
Vorbis vendor string and non-STREAMINFO block order. Vorbis comment entries are
represented as a `Map<String, List<String>>`: value order is retained within
each key, but original interleaving between different keys is not represented.

If Java Sound reports the file as unsupported, verify that both
`jflac-java-sound` and `jflac` are on the runtime classpath. The SPI module does
not bundle native libraries itself; native loading remains owned by `jflac`.

## Runnable Examples

The `examples` project contains small programs that demonstrate the public API
against a FLAC file supplied by the user. They read metadata, decode a bounded
range, stream-decode through `InputStream`, reuse a seekable-channel decoder,
encode to file/`OutputStream`/`SeekableByteChannel`, and edit metadata on a
copy of the input file.

Run the Java example from the repository root:

```powershell
.\gradlew.bat :examples:run --args="music.flac"
```

Run the Kotlin example:

```powershell
.\gradlew.bat :examples:runKotlinFeatureDemo --args="music.flac"
```

Build the FLAC/Ogg FLAC terminal player and launch it directly in Windows
Terminal. The direct script is important: it makes the player the foreground
process attached to the real Windows console, so the native TUI can use the
terminal's configured font, scaling, keyboard, and mouse. With no file argument
it opens a file-system browser:

```powershell
.\gradlew.bat :examples:installDist
.\examples\build\install\jflac-feature-demo\bin\jflac-playback.bat
```

The main screen provides `Open`, `Play`/`Pause`, `Stop`, `Help`, ten-second
seek, and `Quit` buttons. The timeline can be focused with `Tab`, clicked to
seek, or dragged to preview a position. A mouse drag commits exactly one seek
when the button is released; this avoids repeatedly reopening and decoding a
sequential FLAC stream for every mouse-move event.

Pass a file directly to skip the initial browser, including a path containing
spaces:

```powershell
.\examples\build\install\jflac-feature-demo\bin\jflac-playback.bat "music folder\My Song.flac"
```

The named form also accepts an initial playback position. Times may be seconds,
`mm:ss`, or `hh:mm:ss`:

```powershell
.\examples\build\install\jflac-feature-demo\bin\jflac-playback.bat --file "music.flac" --start 01:30
```

`runJavaSoundPlayback` remains useful for Gradle-driven console or headless
runs. Gradle `JavaExec` normally connects its child through pipes rather than a
real terminal handle, so automatic mode detects that condition and uses the
line-command interface instead of opening a graphical terminal emulator:

```powershell
.\gradlew.bat :examples:runJavaSoundPlayback
.\gradlew.bat :examples:runJavaSoundPlayback --args='--console "music.flac"'
```

### TUI controls

Every mouse action has a keyboard equivalent. The file browser supports normal
focus navigation, arrow keys, `Enter`, and cancellation as well as clicking.
It shows the local file system and accepts `.flac`, `.oga`, and `.ogg` files;
an invalid selection reports an error and returns to the player instead of
terminating it.

| Keyboard | Action |
| --- | --- |
| `O` | Open the file-system browser; cancelling keeps the current track |
| `Space` or `P` | Play, pause, resume, or replay an ended track |
| `S` | Stop playback while retaining the selected track |
| `J` / `L` | Move backwards / forwards by ten seconds |
| `Tab` / `Shift+Tab` | Move focus between the timeline and buttons |
| Timeline `Left` / `Right` | Move backwards / forwards by five seconds |
| Timeline `Page Up` / `Page Down` | Move backwards / forwards by thirty seconds |
| Timeline `Home` / `End` | Seek to the beginning / end |
| `?` | Show the in-player control reference |
| `Q` or `Escape` | Close the player and restore the terminal |

| Mouse | Action |
| --- | --- |
| Click `Open`, `Play`/`Pause`, `Stop`, seek, `Help`, or `Quit` | Activate that button |
| Click the timeline | Seek to the clicked position on release |
| Drag the timeline | Preview the clamped position, then seek once on release |
| Click in the file browser | Select directories, files, or dialog buttons |

Seeking is disabled when a source has no known duration. Button enablement and
the `Play`/`Pause` label follow the current playback state.

### Arguments and non-TUI modes

Use `--paused` to load a supplied track without immediately playing. Use
`--no-controls` for a non-interactive play-to-end process; this mode requires a
file and does not read standard input. When no file is supplied, `--start` and
`--paused` apply to the first valid file selected in the browser:

```powershell
.\gradlew.bat :examples:runJavaSoundPlayback --args='--file "music.flac" --no-controls'
```

The same modes can be launched from Bash:

```bash
./gradlew :examples:installDist
./examples/build/install/jflac-feature-demo/bin/jflac-playback --file "music.flac" --start 01:30
./gradlew :examples:runJavaSoundPlayback --args='--file "music.flac" --no-controls'
```

Use `--console` to retain the line-command interface. This is useful in a basic
terminal that cannot provide the TUI capabilities, and it is also convenient
when commands are being supplied as lines of text:

```powershell
.\gradlew.bat :examples:runJavaSoundPlayback --args='--console "music.flac"'
```

Available `--console` commands are:

| Command | Action |
| --- | --- |
| `p`, `pause`, `resume` | Toggle, pause, or resume playback |
| `seek 01:30` | Jump to an absolute playback time |
| `+10`, `-10` | Move forwards or backwards by ten seconds |
| `status` | Print the current position, duration, and percentage |
| `open [path]` | Stop the current track and select another file |
| `list` | List music files in the working directory |
| `help` | Show command help |
| `q` | Stop playback and exit |

Use `--tui` to require the full native TUI. If no real terminal is attached,
forced TUI mode reports how to use the direct launcher instead of falling back.
Without `--tui`, the frontend selection is automatic. Run with `--help` for the
complete option summary; `--tui`, `--console`, and `--no-controls` are mutually
exclusive.

### Terminal and platform behaviour

The widget and file-dialog layers use Lanterna's platform-neutral APIs. A thin
adapter connects that renderer to JLine 4.3.1's system-terminal API. On Windows,
JLine's JNI provider owns Win32 raw mode, virtual-terminal output, visible
window sizing, resize notifications, and native mouse input. It converts key
and mouse console records to X10-style input; the adapter also normalises
Windows drag records before Lanterna handles them. No AWT or Swing window is
created, so the application neither bundles nor chooses a font; Windows
Terminal controls font family, size, DPI scaling, and rendering.

The same bridge is designed for other operating systems: JLine acquires the
system terminal, while Lanterna uses the terminal's portable mouse protocol on
non-Windows platforms. The code does not invoke platform-specific shell
commands or hard-code path separators. Compatible terminals still vary in
colour depth, character width, and mouse support, so every mouse operation has
a keyboard equivalent.

The TUI deliberately rejects dumb or redirected terminals. Automatic mode then
uses the line-command frontend; `--console` selects it explicitly and
`--no-controls` is suitable for automation. Actual playback also depends on a
native jflac binary for the current platform and an available Java Sound output
mixer.

The demo streams PCM instead of loading the whole track into memory. Java
Sound's decoded `AudioInputStream` is sequential, so a seek reopens the source
and decodes/discards PCM up to the requested time. Seeking far into a long file
can therefore take a moment. The player uses Java Sound's normal single-link
Ogg path; use the direct `FlacDecoder` API for chained Ogg decoding.

The TUI controller, immutable view state, timeline calculations, seek-bar input
policy, and native-terminal bridge are separated from the Java Sound session.
Their ordinary tests use fake audio and terminal backends; they do not open a
mixer, sound device, real terminal, or file dialog. Run them with
`./gradlew :examples:test`; real terminal and audio-device behaviour remains an
optional manual smoke test.

The same distribution also contains the separate API feature-demo script:

```powershell
.\gradlew.bat :examples:installDist
.\examples\build\install\jflac-feature-demo\bin\jflac-feature-demo.bat music.flac
```

The examples write generated FLAC files and edited metadata copies under
`build/jflac-feature-demo/`. They do not modify the input file directly.

## Choosing an I/O API

Pick the target that matches the operations the caller needs:

| Target | Decode | Encode | Best fit | Notes |
| --- | --- | --- | --- | --- |
| `Path` | Native FLAC or Ogg FLAC whole file, chunked or pull decode, ranged decode, reusable decode sessions | Native FLAC or Ogg FLAC `open(...)` sessions and one-shot `encode(...)` | Normal files | Strongest encode path for exact final STREAMINFO because libFLAC owns a seekable file target. Ogg output is selected with `FlacEncodingOptions`, not by extension. Metadata reading is file-based for native FLAC and Ogg FLAC; metadata editing is native FLAC only. |
| `InputStream` | Native FLAC or Ogg FLAC whole-stream, chunked, or pull decode | Not applicable | Network responses, classpath resources, existing Java streams | Sequential-only in V1. No range decode or reusable seek session; pull sessions cannot rewind. No metadata editor. The stream is not closed by jflac. |
| `OutputStream` | Not applicable | Native FLAC or Ogg FLAC sequential `open(...)` sessions and one-shot `encode(...)` | HTTP responses, byte-array streams, caller-managed streams | Produces valid FLAC, but libFLAC cannot seek back to patch final STREAMINFO statistics. The stream is flushed on finish but not closed. |
| `SeekableByteChannel` | Native FLAC or Ogg FLAC whole-channel, pull, ranged, or reusable decode | Native FLAC or Ogg FLAC `open(...)` sessions and one-shot `encode(...)` with seek/tell callbacks | Embedded FLAC data, custom storage, caller-managed seekable targets | The current channel position is treated as byte offset zero. The channel is not closed by jflac. |

`InputStream`, `OutputStream`, and `SeekableByteChannel` overloads are standard
Java types, so they are usable from both Java and Kotlin. Session objects are
stateful; do not use the same decode or encode session concurrently from
multiple threads.

## Read Metadata

```kotlin
import org.zzvsjs.jflac.FlacMetadataReader
import java.nio.file.Path

val metadata = FlacMetadataReader().read(Path.of("music.flac"))
println(metadata.streamInfo.sampleRate)
println(metadata.streamInfo.md5Signature.contentToString())
println(metadata.vorbisComment?.comments)
println(metadata.applicationBlocks)
println(metadata.seekTables)
println(metadata.cueSheets)
```

## Validate FLAC Format Values

Use libFLAC-backed validators before building encoder or metadata inputs:

```kotlin
import org.zzvsjs.jflac.FlacFormat
import org.zzvsjs.jflac.FlacPicture

println(FlacFormat.isSampleRateValid(44100))
println(FlacFormat.isSampleRateSubset(44100))
println(FlacFormat.isBlockSizeSubset(blockSize = 4096, sampleRate = 44100))
println(FlacFormat.isVorbisCommentEntryLegal("TITLE=Example"))

val picture = FlacPicture(
    type = 3,
    mimeType = "image/png",
    description = "Cover",
    width = 1,
    height = 1,
    depth = 24,
    colors = 0,
    data = byteArrayOf(0x01, 0x23)
)

println(FlacFormat.pictureViolation(picture))
```

## Decode

Decode the whole file into one interleaved PCM buffer:

```kotlin
import org.zzvsjs.jflac.FlacDecoder
import java.nio.file.Path

val decoded = FlacDecoder().decode(Path.of("music.flac"))
println(decoded.streamInfo)
println(decoded.totalFrames)
```

Ask libFLAC to verify the decoded PCM against a non-zero STREAMINFO MD5
signature:

```kotlin
import org.zzvsjs.jflac.FlacDecodingOptions

val checkedDecoder = FlacDecoder(FlacDecodingOptions(checkMd5 = true))
val checked = checkedDecoder.decode(Path.of("music.flac"))
```

MD5 checking requires a complete decode from the beginning through physical
end-of-stream. The checked decoder therefore rejects seek, range, reusable,
and pull-session APIs. A stream whose STREAMINFO MD5 is all zero still decodes
successfully because it contains no checksum for libFLAC to compare.

Decode every compatible link in a chained Ogg FLAC source:

```kotlin
val chained = FlacDecoder(
    FlacDecodingOptions(decodeChainedOgg = true)
).decode(Path.of("concert.oga"))
```

Chained decoding is available for complete whole-stream decode through
`Path`, `InputStream`, or `SeekableByteChannel`. Native FLAC input and seek,
range, reusable-session, and pull-session operations reject this option. Every
link must use the same sample rate, channel count, and bit depth. The returned
`FlacStreamInfo` retains that common PCM shape, but reports aggregate total
samples, block/frame ranges, and MD5 as unknown or zero; use the result or
summary `totalFrames` for the combined frame count.

`checkMd5` may be combined with chained Ogg decoding. libFLAC then validates
every link that carries a non-zero STREAMINFO signature, including
intermediate links.

Stream interleaved chunks:

```kotlin
import org.zzvsjs.jflac.FlacDecoder
import org.zzvsjs.jflac.FlacInterleavedPcmHandler
import java.nio.file.Path

val summary = FlacDecoder().decodeInterleaved(
    path = Path.of("music.flac"),
    onChunk = FlacInterleavedPcmHandler { chunk ->
        println("${chunk.frames} frames at ${chunk.firstFrameIndex}")
    }
)

println(summary.totalFrames)
```

Decode model details:

- `decode(...)` returns `FlacDecodedAudio` and buffers all decoded PCM in one
  JVM `IntArray`. Prefer streaming callbacks for long files.
- `decodeInterleaved(...)` emits `FlacInterleavedPcmChunk` values. Samples are
  ordered by frame, then channel. For stereo, the layout is left, right, left,
  right, and so on.
- `decodeChannels(...)` emits `FlacChannelPcmChunk` values. Each channel gets
  its own `IntArray`; this is friendlier for per-channel processing but requires
  an extra copy from the native interleaved callback layout.
- `firstSample` is a zero-based PCM frame index in the original FLAC stream.
  `maxFrames` is a frame count, not an interleaved sample count.
- A range that starts exactly at end-of-stream completes with zero PCM frames.
  A range that starts after end-of-stream throws `FlacDecodeException`.

Pull PCM only when the consumer is ready:

```kotlin
import org.zzvsjs.jflac.FlacDecoder
import java.nio.file.Path

FlacDecoder().openPull(Path.of("music.flac")).use { session ->
    val framesPerRead = 4096
    val buffer = IntArray(framesPerRead * session.streamInfo.channels)

    while (true) {
        val frames = session.readInterleaved(buffer, framesPerRead)
        if (frames == -1) break

        val validSamples = frames * session.streamInfo.channels
        // Consume buffer indices 0 until validSamples.
    }
}
```

`openPull(...)` accepts a `Path`, `InputStream`, or `SeekableByteChannel` and
advances libFLAC only when `readInterleaved(...)` is called. The buffer needs
space for `maxFrames * channels` samples. The return value is a frame count;
only the first `frames * channels` samples are valid, and `-1` means
end-of-stream. Close the session after use. Caller-provided streams and
channels remain caller-owned.

Reuse one native decoder for repeated ranges:

```kotlin
import org.zzvsjs.jflac.FlacDecoder
import org.zzvsjs.jflac.FlacInterleavedPcmHandler
import java.nio.file.Path

FlacDecoder().open(Path.of("music.flac")).use { session ->
    session.decodeInterleaved(
        firstSample = 0,
        maxFrames = 4096,
        onChunk = FlacInterleavedPcmHandler { chunk ->
            println(chunk.frames)
        }
    )
}
```

Decode sequential native FLAC or Ogg FLAC streams:

```kotlin
import org.zzvsjs.jflac.FlacDecoder
import org.zzvsjs.jflac.FlacInterleavedPcmHandler
import java.io.InputStream

fun inspect(input: InputStream) {
    val summary = FlacDecoder().decodeInterleaved(
        input = input,
        onChunk = FlacInterleavedPcmHandler { chunk ->
            println(chunk.frames)
        }
    )

    println(summary.totalFrames)
}
```

Plain streams use the native or Ogg stream decoder after the wrapper inspects
the first bytes, and provide only a read callback. Because there is no
seek/tell/length contract, the stream overloads intentionally do not expose
range decode or reusable seek sessions. An `openPull(...)` stream session is
still sequential and cannot rewind.

Decode seekable native FLAC or Ogg FLAC channels when callers need ranges or reusable
sessions without passing a file path:

```kotlin
import org.zzvsjs.jflac.FlacDecoder
import org.zzvsjs.jflac.FlacInterleavedPcmHandler
import java.nio.channels.SeekableByteChannel

fun inspectRange(input: SeekableByteChannel) {
    FlacDecoder().open(input).use { session ->
        session.decodeInterleaved(
            firstSample = 2048,
            maxFrames = 4096,
            onChunk = FlacInterleavedPcmHandler { chunk ->
                println(chunk.firstFrameIndex)
            }
        )
    }
}
```

Seekable channel decode also chooses the matching native or Ogg stream decoder,
and provides read, seek, tell, length, and EOF callbacks. The channel position
when the decode or session is opened becomes the FLAC stream origin, so callers
can decode FLAC data embedded after a prefix in a larger channel.

## Encode

```kotlin
import org.zzvsjs.jflac.FlacAudioFormat
import org.zzvsjs.jflac.FlacEncoder
import org.zzvsjs.jflac.FlacEncodingMetadata
import java.nio.file.Path

val format = FlacAudioFormat(
    sampleRate = 44100,
    channels = 2,
    bitsPerSample = 16,
    totalSamplesEstimate = null
)

val samples = IntArray(44100 * 2)

FlacEncoder().encode(
    output = Path.of("out.flac"),
    format = format,
    samples = samples,
    metadata = FlacEncodingMetadata(
        comments = mapOf("TITLE" to listOf("Example"))
    )
)
```

Encode model details:

- PCM input is signed integer PCM in interleaved frame order.
- `FlacAudioFormat.channels` determines how `samples` is split into frames.
  The sample array length must equal `frames * channels` for session writes.
- `totalSamplesEstimate` is optional. It lets libFLAC write a known total when
  the caller already knows it, but non-seekable `OutputStream` output still
  cannot receive every final back-patched STREAMINFO statistic.
- Use `open(...)` when PCM arrives in several chunks. Use one-shot
  `encode(...)` when the caller already has a complete interleaved buffer.
- Metadata is attached when the encoder is opened. It cannot be changed after
  audio frames start because FLAC metadata is written before the frames.
- `FlacEncodingOptions.container` selects native FLAC or Ogg FLAC output.
  File extensions are not inspected. For Ogg FLAC, set `oggSerialNumber` when
  the stream may be multiplexed with other Ogg streams.
- `FlacEncodingOptions.numThreads` accepts `1..128`. The default `1` uses the
  traditional single-threaded encoder. Values above one request the FLAC 1.5
  parallel encoder and require pthread support. Linux and macOS builds enable
  it; the Windows MSVC runtime rejects values above one with
  `UnsupportedFeatureException` because it has no pthread backend.

Encode Ogg FLAC by selecting the Ogg container:

```kotlin
import org.zzvsjs.jflac.FlacEncoder
import org.zzvsjs.jflac.FlacEncodingContainer
import org.zzvsjs.jflac.FlacEncodingOptions
import java.nio.file.Path

FlacEncoder().encode(
    output = Path.of("out.oga"),
    format = format,
    samples = samples,
    options = FlacEncodingOptions(
        container = FlacEncodingContainer.OGG,
        oggSerialNumber = 1234
    )
)
```

Encode sequential native FLAC streams:

```kotlin
import org.zzvsjs.jflac.FlacAudioFormat
import org.zzvsjs.jflac.FlacEncoder
import java.io.OutputStream

fun writeFlac(output: OutputStream, samples: IntArray) {
    val format = FlacAudioFormat(
        sampleRate = 44100,
        channels = 2,
        bitsPerSample = 16,
        totalSamplesEstimate = samples.size.toLong() / 2
    )

    FlacEncoder().encode(
        output = output,
        format = format,
        samples = samples
    )
}
```

Output streams are sequential in V1. The encoder does not receive seek/tell
callbacks, so libFLAC cannot back-patch final STREAMINFO statistics after the
last PCM frame. The output is still valid native FLAC; use file output or a
seekable channel when you need libFLAC to seek back and update final
STREAMINFO fields.

Encode seekable channels when the output target is not a file path but can seek
and tell:

```kotlin
import org.zzvsjs.jflac.FlacAudioFormat
import org.zzvsjs.jflac.FlacEncoder
import java.nio.channels.SeekableByteChannel

fun writeSeekable(output: SeekableByteChannel, samples: IntArray) {
    val format = FlacAudioFormat(
        sampleRate = 44100,
        channels = 2,
        bitsPerSample = 16
    )

    FlacEncoder().encode(
        output = output,
        format = format,
        samples = samples
    )
}
```

For exact block-order metadata round trips, read `FlacMetadata.blocks` and pass
it through `FlacEncodingMetadata(blocks = ...)`. The list preserves
non-STREAMINFO block order, and the bundled libFLAC patch preserves the vendor
supplied by an ordered Vorbis comment block. Grouped comments supplied through
`FlacEncodingMetadata.comments` still use libFLAC's encoder vendor.

Vorbis comment entries themselves are not an exact sequence round trip. The
public model groups entries as a `Map<String, List<String>>`, preserving value
order within each key but not the original interleaving between different
keys. Entries may therefore be regrouped when they are written.

## Java Callers

Kotlin functional handlers are exposed as Java SAM interfaces, and listener
adapters are provided for callers that prefer subclassing.

```java
import org.zzvsjs.jflac.FlacDecodeAdapter;
import org.zzvsjs.jflac.FlacDecodeSummary;
import org.zzvsjs.jflac.FlacDecoder;
import org.zzvsjs.jflac.FlacInterleavedPcmChunk;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

try (InputStream input = Files.newInputStream(Path.of("music.flac"))) {
    FlacDecodeSummary summary = new FlacDecoder().decode(
        input,
        new FlacDecodeAdapter() {
            @Override
            public void onInterleavedPcm(FlacInterleavedPcmChunk chunk) {
                System.out.println(chunk.getFrames());
            }
        }
    );

    System.out.println(summary.getTotalFrames());
}
```

Java encoder use can stay close to normal `java.io` code:

```java
import org.zzvsjs.jflac.FlacAudioFormat;
import org.zzvsjs.jflac.FlacEncoder;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

int[] samples = new int[44100 * 2];
FlacAudioFormat format = new FlacAudioFormat(44100, 2, 16);

try (OutputStream output = Files.newOutputStream(Path.of("out.flac"))) {
    new FlacEncoder().encode(output, format, samples);
}
```

## Edit Existing Metadata

Existing-file metadata edits are separate from encoding. The editor updates the
metadata chain in the FLAC file and preserves the audio frames; it does not
decode or re-encode PCM.

```kotlin
import org.zzvsjs.jflac.FlacMetadataEditor
import java.nio.file.Path

FlacMetadataEditor().edit(Path.of("music.flac")) { session ->
    session.setVorbisComments(
        mapOf(
            "TITLE" to listOf("Edited title"),
            "ARTIST" to listOf("Example Artist")
        )
    )
}
```

For exact block-level edits, replace the ordered non-STREAMINFO block list:

```kotlin
import org.zzvsjs.jflac.FlacMetadataBlock
import org.zzvsjs.jflac.FlacMetadataEditor
import org.zzvsjs.jflac.FlacEncodingMetadata
import org.zzvsjs.jflac.FlacMetadataReader
import java.nio.file.Path

val editor = FlacMetadataEditor()
val metadata = FlacMetadataReader().read(Path.of("music.flac"))

editor.replace(
    Path.of("music.flac"),
    FlacEncodingMetadata(
        blocks = metadata.blocks.filterNot { block -> block is FlacMetadataBlock.Picture }
    )
)
```

By default, the editor allows libFLAC to use existing padding so small edits can
avoid rewriting the whole file. If the new metadata does not fit, libFLAC may
rewrite the file internally while still preserving the audio stream.

The metadata editor remains file-only. It intentionally does not accept
`InputStream`, `OutputStream`, or `SeekableByteChannel` because libFLAC's safe
metadata-chain editing API works on files and can rewrite the file when padding
is not enough.

## Native Design

The public Kotlin and Java API exposes neither native pointers nor JNI
declarations. `org.zzvsjs.jflac.internal.NativeBindings` and its transport
classes are package-private. The Kotlin `NativeAccess` façade is internal and
its callable methods are JVM-synthetic; the complete internal package is
omitted from published API documentation. Public decoder and encoder sessions
carry opaque handles internally; the C layer validates those handles before
touching libFLAC state.

The platform JNI shim dynamically resolves libFLAC from its absolute sibling
path. Startup verifies the bundled runtime's FLAC 1.5.0 version, API version,
Ogg capability, and every symbol derived from the C resolver declarations.
The generated JNI header is also compared with the C implementations and the
finished shim's exports. This makes Java signature or libFLAC export drift a
build/startup error rather than a delayed `UnsatisfiedLinkError`. Ogg support
is provided by static libogg linked into the shared libFLAC runtime.

PCM crossing the JNI boundary is interleaved signed integer PCM in Java
`IntArray` values. One frame means one sample per channel, so a stereo chunk of
`N` frames contains `N * 2` integer samples.

Whole-file decode collects all decoded PCM into memory. For large files, prefer
the streaming decode API or ranged decode API so the JVM only receives bounded
chunks.

Stream and channel callbacks are synchronous. If Java code throws while reading,
writing, or consuming PCM, the native callback aborts libFLAC and preserves the
original Java exception instead of replacing it with a generic native failure.

File and channel decoder sessions keep one libFLAC decoder alive for repeated
seek operations. Closing the session releases the native handle; using a closed
or stale handle is treated as an invalid native session.

## Current Limitations

- Native build and loading paths cover Windows x64, Linux x64, macOS x64, and
  macOS Arm64. A normal developer build packages only its host target; the CI
  aggregate job is responsible for the universal four-target JAR.
- Single-link Ogg FLAC decode, encode, and metadata reading are supported.
  Chained decoding is opt-in and whole-stream only; seek, range, reusable,
  pull, and Java Sound SPI paths remain single-link. Existing-file Ogg FLAC
  metadata editing is not supported.
- Parallel encoding is exposed through `FlacEncodingOptions.numThreads`.
  Windows supports only the default value `1` because its MSVC libFLAC build
  has no pthread backend.
- Decoder MD5 checking is available for complete one-shot decode only. It is
  intentionally rejected for seek, range, reusable, and pull-session paths.
- `InputStream` and `OutputStream` APIs are sequential-only. File and
  `SeekableByteChannel` decode APIs support ranges and reusable seek sessions.
  Metadata reading and editing remain file-based.
- Stream and channel overloads do not close caller-provided Java objects.
  Callers should use try-with-resources or Kotlin `use` for their own streams
  and channels.
- Metadata reading exposes STREAMINFO, Vorbis comments, pictures, APPLICATION
  blocks, SEEKTABLE blocks, CUESHEET blocks, PADDING blocks, and raw unknown
  blocks. `FlacMetadata.blocks` preserves the physical order of non-STREAMINFO
  blocks and carries an ordered Vorbis comment block's vendor through
  round-trip workflows. Vorbis entries are grouped by key, so their original
  cross-key order is not preserved. Existing-file metadata editing is
  available through `FlacMetadataEditor`.
- Encoding writes PCM plus Vorbis comments, pictures, APPLICATION blocks,
  SEEKTABLE blocks, CUESHEET blocks, PADDING blocks, and raw unknown blocks. It
  does not yet generate seek points automatically. Use `FlacEncodingMetadata`
  grouped fields for simple typed metadata, or set `blocks` to write the exact
  non-STREAMINFO metadata block order. When `blocks` is set, it is the encoded
  metadata source and the grouped convenience fields are ignored.
- Encoder sessions do not support changing metadata after `open(...)`, because
  FLAC stores metadata before audio frames and libFLAC attaches encoder
  metadata before the output stream starts.

## Native Packaging Layout

A host-built JAR contains the matching pair from one directory; the universal
CI artefact contains all four directories:

```text
META-INF/native/windows-x86_64/FLAC.dll
META-INF/native/windows-x86_64/jflac-jni.dll

META-INF/native/linux-x86_64/libFLAC.so.14
META-INF/native/linux-x86_64/libjflac-jni.so

META-INF/native/macos-x86_64/libFLAC.14.dylib
META-INF/native/macos-x86_64/libjflac-jni.dylib

META-INF/native/macos-aarch64/libFLAC.14.dylib
META-INF/native/macos-aarch64/libjflac-jni.dylib
```

libogg is statically linked into each bundled libFLAC runtime; no separate
libogg shared library is packaged. The loader selects the exact OS and
architecture directory, verifies extracted files by SHA-256, and replaces a
stale or corrupted extraction atomically before loading the dependency and JNI
shim in that order.

## Licences

The wrapper is licensed under `LICENSE`. The bundled libFLAC binary and its
statically linked libogg code are covered by the upstream licences reproduced
in `THIRD_PARTY_NOTICES.md`; both files are also included under `META-INF` in
the built JAR.
