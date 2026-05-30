package org.zzvsjs.jflac

/**
 * Declares the PCM layout the encoder should expect from the caller.
 *
 * The wrapper keeps this model intentionally small so Java and Kotlin callers
 * can map existing PCM pipelines into libFLAC without learning native details.
 */
data class FlacAudioFormat @JvmOverloads constructor(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val totalSamplesEstimate: Long? = null
)

/**
 * High-level tuning knobs exposed for the first encoder iteration.
 *
 * The wrapper intentionally exposes only the stable options that map cleanly to
 * libFLAC's common file encoder configuration path.
 */
data class FlacEncodingOptions @JvmOverloads constructor(
    val compressionLevel: Int = 5,
    val verify: Boolean = true,
    val streamableSubset: Boolean = true,
    val blockSize: Int? = null
)

/**
 * Metadata blocks that may be written alongside encoded audio frames.
 *
 * Comments are flattened into repeated Vorbis `KEY=value` entries before they
 * cross into JNI. Picture, APPLICATION, SEEKTABLE, CUESHEET, PADDING, and
 * unknown blocks reuse the read models so callers can round-trip metadata
 * without translating between separate read and write shapes.
 *
 * When [blocks] is not empty, the encoder writes that ordered non-STREAMINFO
 * block list and ignores the grouped convenience fields. This keeps exact
 * metadata order available without breaking the older typed-list API.
 */
data class FlacEncodingMetadata @JvmOverloads constructor(
    val comments: Map<String, List<String>> = emptyMap(),
    val pictures: List<FlacPicture> = emptyList(),
    val applicationBlocks: List<FlacApplicationBlock> = emptyList(),
    val seekTables: List<FlacSeekTable> = emptyList(),
    val cueSheets: List<FlacCueSheet> = emptyList(),
    val paddingBlocks: List<FlacPaddingBlock> = emptyList(),
    val unknownBlocks: List<FlacUnknownMetadataBlock> = emptyList(),
    val blocks: List<FlacMetadataBlock> = emptyList()
)

/**
 * Builds encoder metadata from a read metadata snapshot.
 *
 * STREAMINFO is intentionally omitted because libFLAC writes encoder-derived
 * STREAMINFO for the new audio stream. The Vorbis vendor string is also not
 * preserved because libFLAC writes its own encoder vendor value.
 */
fun FlacMetadata.toEncodingMetadata(): FlacEncodingMetadata {
    return FlacEncodingMetadata(
        comments = vorbisComment?.comments.orEmpty(),
        pictures = pictures,
        applicationBlocks = applicationBlocks,
        seekTables = seekTables,
        cueSheets = cueSheets,
        paddingBlocks = paddingBlocks,
        unknownBlocks = unknownBlocks,
        blocks = blocks
    )
}

/**
 * Active encoder session that accepts interleaved signed PCM frames.
 *
 * The session owns a native encoder handle. Callers should always finish the
 * session, either explicitly or through `use`, so libFLAC can flush the output
 * deterministically.
 */
interface FlacEncodingSession : AutoCloseable {
    /**
     * Encodes one chunk of channel-interleaved PCM data.
     *
     * `frames` is the number of audio frames in [samples], not the raw sample
     * count. The valid element count is always `frames * channels`.
     */
    fun writeInterleaved(samples: IntArray, frames: Int)

    /**
     * Finalizes the FLAC stream and closes the native encoder.
     *
     * Repeated calls after a successful finish are ignored so callers can use
     * `finish()` and `close()` interchangeably.
     */
    fun finish()

    /**
     * Delegates to [finish] so Kotlin `use` and Java try-with-resources behave
     * the same way for successful encode flows.
     */
    override fun close() = finish()
}
