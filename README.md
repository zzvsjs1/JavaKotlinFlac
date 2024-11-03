# JavaKotlinFlac

Java and Kotlin JNI wrapper for libFLAC. The current artefact bundles a
Windows x64 native runtime and exposes file-based metadata, decode and encode
APIs.

## Status

- JVM target: Java 17
- Native runtime: Windows x64 only
- Bundled native libraries: `FLAC.dll`, `jflac-jni.dll`
- MSVC runtime: statically linked into the bundled Windows DLLs
- FLAC source: official FLAC 1.5.0 archive, SHA-256 checked during build
- Ogg FLAC: not supported, because the bundled libFLAC build uses `WITH_OGG=OFF`
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

The build downloads FLAC 1.5.0, verifies the archive checksum, builds
`FLAC.dll`, builds `jflac-jni.dll`, verifies required exports, and stages the
native files into the JAR resources.

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
bundled Windows DLLs from the JAR, and decodes a small range from `music.flac`.
It also encodes a deterministic PCM buffer, reads the metadata back, and
decodes the output again to verify the published artefact.

Consumer dependency after local publication:

```kotlin
dependencies {
    implementation("org.zzvsjs:jflac:0.1.0-SNAPSHOT")
}
```

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

For exact metadata round trips, read `FlacMetadata.blocks` and pass it through
`FlacEncodingMetadata(blocks = ...)`. The list preserves non-STREAMINFO block
order; libFLAC still generates STREAMINFO and its own Vorbis vendor string for
the new encoded audio.

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
import org.zzvsjs.jflac.FlacMetadataReader
import java.nio.file.Path

val editor = FlacMetadataEditor()
val metadata = FlacMetadataReader().read(Path.of("music.flac"))

editor.writeBlocks(
    Path.of("music.flac"),
    metadata.blocks.filterNot { block -> block is FlacMetadataBlock.Picture }
)
```

By default, the editor allows libFLAC to use existing padding so small edits can
avoid rewriting the whole file. If the new metadata does not fit, libFLAC may
rewrite the file internally while still preserving the audio stream.

## Native Design

The public Kotlin and Java API does not expose native pointers. Reusable
decoders and active encoders are represented by opaque handles in
`NativeBindings`, and the C layer validates those handles before touching
libFLAC state.

`jflac-jni.dll` dynamically resolves the libFLAC symbols it uses from the
bundled `FLAC.dll` at runtime. This keeps the JNI wrapper in control of error
messages when the FLAC runtime is missing or incompatible, but it also means the
bundled FLAC DLL must contain every export expected by the wrapper build.

PCM crossing the JNI boundary is interleaved signed integer PCM in Java
`IntArray` values. One frame means one sample per channel, so a stereo chunk of
`N` frames contains `N * 2` integer samples.

Whole-file decode collects all decoded PCM into memory. For large files, prefer
the streaming decode API or ranged decode API so the JVM only receives bounded
chunks.

## Current Limitations

- Native packaging is currently Windows x64 only. The resource layout is ready
  for future platforms, but Linux and macOS DLL/shared-library builds are not
  produced yet.
- Ogg FLAC is not supported because the bundled libFLAC build disables Ogg.
- APIs are file-based. There is no custom stream, socket, memory-buffer, or
  callback I/O API yet.
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

The loader detects the current platform and currently accepts only
`windows-x86_64`. Future Linux or macOS support should add new directories with
the same shape and extend the platform detector:

```text
META-INF/native/<platform>/FLAC.dll or platform libFLAC equivalent
META-INF/native/<platform>/jflac-jni.dll or platform JNI library equivalent
```

## Licences

The wrapper is licensed under `LICENSE`. The bundled libFLAC binary is covered
by the upstream FLAC licence reproduced in `THIRD_PARTY_NOTICES.md`; both files
are also included under `META-INF` in the built JAR.
