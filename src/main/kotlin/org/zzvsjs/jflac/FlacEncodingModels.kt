package org.zzvsjs.jflac

/**
 * Declares the PCM layout the encoder should expect from the caller.
 *
 * The wrapper keeps this model intentionally small so Java and Kotlin callers
 * can map existing PCM pipelines into libFLAC without learning native details.
 *
 * A frame is one time position across all channels. For stereo, an interleaved
 * sample array with 2,000 values contains 1,000 frames because every frame has
 * one left sample and one right sample.
 *
 * @property sampleRate sample rate in hertz. It must be positive and should
 *   pass [FlacFormat.isSampleRateValid] when accepting user input.
 * @property channels channel count, currently limited to `1..8` by the wrapper
 *   and libFLAC stream constraints.
 * @property bitsPerSample signed PCM bit depth. Values must fit the declared
 *   range when samples are written.
 * @property totalSamplesEstimate optional frame count for the whole output. A
 *   `null` value means "unknown"; a non-null value must be non-negative.
 */
data class FlacAudioFormat @JvmOverloads constructor(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val totalSamplesEstimate: Long? = null
)

/**
 * Container used by encoder output.
 *
 * Native FLAC is the existing `.flac` bitstream. Ogg FLAC wraps the same FLAC
 * frames in Ogg pages and is selected explicitly instead of by file extension.
 */
enum class FlacEncodingContainer(internal val nativeCode: Int) {
    NATIVE(0),
    OGG(1)
}

/**
 * High-level tuning knobs exposed for the first encoder iteration.
 *
 * The wrapper intentionally exposes only the stable options that map cleanly to
 * libFLAC's common file encoder configuration path.
 *
 * @property compressionLevel libFLAC compression level `0..8`; higher levels
 *   usually spend more CPU to reduce file size.
 * @property verify enables libFLAC's encode verification pass.
 * @property streamableSubset keeps output inside FLAC's streamable subset.
 * @property blockSize optional frame block size. Non-null values must be
 *   positive and should pass [FlacFormat.isBlockSizeSubset] when
 *   [streamableSubset] is true.
 * @property container selects native FLAC or Ogg FLAC. File extensions are not
 *   inspected by the encoder.
 * @property oggSerialNumber optional Ogg stream serial number. It is only valid
 *   when [container] is [FlacEncodingContainer.OGG].
 */
data class FlacEncodingOptions @JvmOverloads constructor(
    val compressionLevel: Int = 5,
    val verify: Boolean = true,
    val streamableSubset: Boolean = true,
    val blockSize: Int? = null,
    val container: FlacEncodingContainer = FlacEncodingContainer.NATIVE,
    val oggSerialNumber: Int? = null
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
 *
 * Example:
 *
 * ```
 * val metadata = FlacEncodingMetadata(
 *     comments = mapOf("TITLE" to listOf("Example"))
 * )
 * ```
 *
 * Important limits are checked before JNI is entered: Vorbis comments cannot
 * contain embedded NUL characters, APPLICATION IDs must be exactly four bytes,
 * metadata block bodies must fit the FLAC 24-bit length field, and ordered
 * metadata may contain at most one Vorbis comment block and one SEEKTABLE.
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
 * STREAMINFO for the new audio stream. Ordered Vorbis comment blocks preserve
 * their vendor, while grouped comments use libFLAC's encoder vendor.
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
 *
 * The session is terminal after [finish] or after a native write failure. A
 * later write then throws [IllegalStateException] instead of trying to reuse an
 * encoder whose native state may already have been released.
 */
interface FlacEncodingSession : AutoCloseable {
    /**
     * Encodes one chunk of channel-interleaved PCM data.
     *
     * `frames` is the number of audio frames in [samples], not the raw sample
     * count. The valid element count is always `frames * channels`.
     *
     * Signed sample ranges are validated against the declared bit depth before
     * JNI is called. For example, 16-bit PCM must be in `-32768..32767`, and
     * 24-bit PCM must be in `-8388608..8388607`.
     *
     * @throws IllegalArgumentException when [frames] is negative, the array size
     *   does not match `frames * channels`, or a sample is outside the declared
     *   signed range.
     * @throws IllegalStateException when the session has already finished or
     *   failed.
     * @throws FlacEncodeException when libFLAC rejects or fails to encode the
     *   chunk.
     */
    fun writeInterleaved(samples: IntArray, frames: Int)

    /**
     * Finalises the FLAC stream and closes the native encoder.
     *
     * Repeated calls after a successful finish are ignored so callers can use
     * `finish()` and `close()` interchangeably.
     *
     * @throws FlacEncodeException when libFLAC cannot finish the stream or flush
     *   the final output.
     */
    fun finish()

    /**
     * Delegates to [finish] so Kotlin `use` and Java try-with-resources behave
     * the same way for successful encode flows.
     */
    override fun close() = finish()
}
