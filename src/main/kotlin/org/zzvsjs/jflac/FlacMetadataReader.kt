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
 */
class FlacMetadataReader {
    fun read(path: Path): FlacMetadata {
        val normalizedPath = validateNativeFlacPath(path)
        FlacNativeLoader.load()
        return NativeBindings.readMetadata(normalizedPath.absolutePathString()).toPublicMetadata()
    }
}
