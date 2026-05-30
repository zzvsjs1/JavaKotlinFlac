# JavaKotlinFlac

Java and Kotlin JNI wrapper for libFLAC. The current artefact bundles a
Windows x64 native runtime, file-based metadata APIs, and file, sequential
stream, and seekable channel APIs for decode and encode workflows.

## Status

- JVM target: Java 17
- Native runtime: Windows x64 only
- Bundled native libraries: `FLAC.dll`, `jflac-jni.dll`
- MSVC runtime: statically linked into the bundled Windows DLLs
- FLAC source: official FLAC 1.5.0 archive, SHA-256 checked during build
- libogg source: official libogg 1.3.6 archive, SHA-256 checked during build
- Ogg FLAC: decode and metadata reading supported; encoding and existing-file
  metadata editing are not supported
- Linux and macOS: not built yet; native resources are organised as
  `META-INF/native/<platform>` so more platform bundles can be added later

## Requirements

- JDK 21 to run the current Gradle build
- Java 17 or newer for consumers
- Windows x64
- Visual Studio C++ tools with the x64 toolchain
- CMake and Ninja from the Visual Studio installation

## Build

```powershell
.\gradlew.bat build
```

The build downloads FLAC 1.5.0 and libogg 1.3.6, verifies both archive
checksums, builds a static `ogg.lib`, builds `FLAC.dll` with Ogg FLAC support,
builds `jflac-jni.dll`, verifies required exports, and stages the native files
into the JAR resources.

For clangd, `buildNative` also generates `compile_commands.json` at the project
root and under `build/native`.

```powershell
.\gradlew.bat buildNative
```

## Local Publication

This project is configured for local Maven publication only. It does not define
remote Maven repositories or signing. Publication is currently Windows-only so
the local artefact always includes the bundled Windows x64 native runtime.
On non-Windows hosts, `publishToMavenLocal` is intentionally blocked to avoid
publishing an artefact without bundled native libraries.

```powershell
.\gradlew.bat publishToMavenLocal
```

To verify the published artefact from a separate Java consumer project:

```powershell
.\gradlew.bat consumerSmokeTest
```

The smoke test resolves `org.zzvsjs:jflac` from `mavenLocal()`, loads the
bundled Windows DLLs from the JAR, and decodes a small range from a generated
FLAC fixture under `build/consumer-smoke/`. It also encodes a deterministic PCM
buffer, reads the metadata back, and decodes the output again to verify the
published artefact.

Consumer dependency after local publication:

```kotlin
dependencies {
    implementation("org.zzvsjs:jflac:0.1.0-SNAPSHOT")
}
```

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

Build an installed command-line program:

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
| `Path` | Native FLAC or Ogg FLAC whole file, chunked decode, ranged decode, reusable decode sessions | Native FLAC `open(...)` sessions and one-shot `encode(...)` | Normal files | Strongest encode path for exact final STREAMINFO because libFLAC owns a seekable file target. Metadata reading is file-based for native FLAC and Ogg FLAC; metadata editing is native FLAC only. |
| `InputStream` | Native FLAC or Ogg FLAC whole stream only | Not applicable | Network responses, classpath resources, existing Java streams | Sequential-only in V1. No range decode, no reusable session, no metadata editor. The stream is not closed by jflac. |
| `OutputStream` | Not applicable | Native FLAC sequential `open(...)` sessions and one-shot `encode(...)` | HTTP responses, byte-array streams, caller-managed streams | Produces valid native FLAC, but libFLAC cannot seek back to patch final STREAMINFO statistics. The stream is flushed on finish but not closed. |
| `SeekableByteChannel` | Native FLAC or Ogg FLAC whole channel, ranged decode, reusable decode sessions | Native FLAC `open(...)` sessions and one-shot `encode(...)` with seek/tell callbacks | Embedded FLAC data, custom storage, caller-managed seekable targets | The current channel position is treated as byte offset zero. The channel is not closed by jflac. |

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

Plain streams are decoded with `FLAC__stream_decoder_init_stream` and only a
read callback. Because there is no seek/tell/length contract, the stream
overloads intentionally do not expose range decode or reusable sessions.

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

Seekable channel decode also uses `FLAC__stream_decoder_init_stream`, but
provides read, seek, tell, length, and EOF callbacks. The channel position when
the decode or session is opened becomes the FLAC stream origin, so callers can
decode FLAC data embedded after a prefix in a larger channel.

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

For exact metadata round trips, read `FlacMetadata.blocks` and pass it through
`FlacEncodingMetadata(blocks = ...)`. The list preserves non-STREAMINFO block
order; libFLAC still generates STREAMINFO and its own Vorbis vendor string for
the new encoded audio.

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

The public Kotlin and Java API does not expose native pointers. Reusable
decoders and active encoders are represented by opaque handles in
`NativeBindings`, and the C layer validates those handles before touching
libFLAC state.

`jflac-jni.dll` dynamically resolves the libFLAC symbols it uses from the
bundled `FLAC.dll` at runtime. This keeps the JNI wrapper in control of error
messages when the FLAC runtime is missing or incompatible, but it also means the
bundled FLAC DLL must contain every export expected by the wrapper build.
Ogg support is provided by a static libogg build linked into `FLAC.dll`.

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

- Native packaging is currently Windows x64 only. The resource layout is ready
  for future platforms, but Linux and macOS DLL/shared-library builds are not
  produced yet.
- Ogg FLAC decode and metadata reading are supported. Ogg FLAC encoding and
  existing-file metadata editing are not supported.
- `InputStream` and `OutputStream` APIs are sequential-only. File and
  `SeekableByteChannel` decode APIs support ranges and reusable seek sessions.
  Metadata reading and editing remain file-based.
- Stream and channel overloads do not close caller-provided Java objects.
  Callers should use try-with-resources or Kotlin `use` for their own streams
  and channels.
- Metadata reading exposes STREAMINFO, Vorbis comments, pictures, APPLICATION
  blocks, SEEKTABLE blocks, CUESHEET blocks, PADDING blocks, and raw unknown
  blocks. `FlacMetadata.blocks` preserves the physical order of non-STREAMINFO
  blocks for round-trip workflows. Existing-file metadata editing is available
  through `FlacMetadataEditor`.
- Encoding writes PCM plus Vorbis comments, pictures, APPLICATION blocks,
  SEEKTABLE blocks, CUESHEET blocks, PADDING blocks, and raw unknown blocks. It
  does not yet generate seek points automatically. Use `FlacEncodingMetadata`
  grouped fields for simple typed metadata, or set `blocks` to write the exact
  non-STREAMINFO metadata block order. When `blocks` is set, it is the encoded
  metadata source and the grouped convenience fields are ignored.
- Encoder sessions do not support changing metadata after `open(...)`, because
  FLAC stores metadata before audio frames and libFLAC attaches encoder
  metadata before the output stream starts.
- The native implementation is Windows-oriented C. Cross-platform support will
  need platform-specific loading, build, packaging, and smoke-test coverage.

## Native Packaging Layout

The current JAR contains:

```text
META-INF/native/windows-x86_64/FLAC.dll
META-INF/native/windows-x86_64/jflac-jni.dll
```

libogg is statically linked into the bundled `FLAC.dll`; there is no separate
`ogg.dll` resource to load.

The loader detects the current platform and currently accepts only
`windows-x86_64`. Future Linux or macOS support should add new directories with
the same shape and extend the platform detector:

```text
META-INF/native/<platform>/FLAC.dll or platform libFLAC equivalent
META-INF/native/<platform>/jflac-jni.dll or platform JNI library equivalent
```

## Licences

The wrapper is licensed under `LICENSE`. The bundled libFLAC binary and its
statically linked libogg code are covered by the upstream licences reproduced
in `THIRD_PARTY_NOTICES.md`; both files are also included under `META-INF` in
the built JAR.
