package org.zzvsjs.jflac

import org.zzvsjs.jflac.internal.NativeBindings
import org.zzvsjs.jflac.internal.toPublicMetadata
import java.nio.file.Path
import kotlin.io.path.absolutePathString

/**
 * File-based FLAC metadata reader backed by libFLAC.
 *
 * The reader performs the same JVM-side path checks as the decoder before
 * asking JNI to materialise STREAMINFO plus the supported non-audio metadata
 * blocks. The returned [FlacMetadata.blocks] list preserves the physical order
 * of non-STREAMINFO blocks for round-trip workflows.
 *
 * Example:
 *
 * ```
 * val metadata = FlacMetadataReader().read(Path.of("song.flac"))
 * println(metadata.streamInfo.sampleRate)
 * println(metadata.vorbisComment?.comments?.get("TITLE"))
 * ```
 *
 * This reader accepts native FLAC and Ogg FLAC files. It loads the native
 * library on demand, so the first call can throw [NativeLoadException] on an
 * unsupported platform or when the bundled native runtime is unavailable.
 * Invalid paths, malformed streams, and libFLAC metadata-chain failures are
 * reported as [IllegalArgumentException] or [FlacDecodeException].
 */
class FlacMetadataReader {
    /**
     * Reads STREAMINFO and supported metadata blocks from [path].
     *
     * [FlacMetadata.blocks] contains non-STREAMINFO blocks in physical file
     * order. The typed lists on [FlacMetadata] are convenience views over the
     * same data and are easier to use when exact ordering is not important.
     */
    fun read(path: Path): FlacMetadata {
        val inspected = inspectNativeFlacPath(path)
        FlacNativeLoader.load()
        return NativeBindings.readMetadata(
            inspected.path.absolutePathString(),
            inspected.container.nativeCode
        ).toPublicMetadata()
    }
}
