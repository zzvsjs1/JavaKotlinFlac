package org.zzvsjs.jflac

/* FLAC metadata block bodies use a 24-bit byte length in the block header. */
private const val FLAC_METADATA_MAX_BLOCK_LENGTH = 0xFF_FFFF

/* libFLAC reserves type codes 7..126 for block types unknown to this wrapper. */
private const val FLAC_UNKNOWN_METADATA_MIN_TYPE = 7
private const val FLAC_UNKNOWN_METADATA_MAX_TYPE = 126

/**
 * STREAMINFO values reported by libFLAC for a decoded file.
 *
 * The field layout closely mirrors the native structure so the wrapper can
 * remain thin and predictable for both Java and Kotlin callers.
 *
 * [totalSamples] is a frame count. For a stereo file with `totalSamples = 10`,
 * the decoded interleaved PCM array contains 20 integer values. A value of
 * zero can mean an empty file or an encoder that did not know the total in
 * advance.
 *
 * @property md5Signature 16-byte STREAMINFO MD5 digest. The array is included
 *   as raw bytes because it is not text.
 */
data class FlacStreamInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val totalSamples: Long,
    val minBlockSize: Int,
    val maxBlockSize: Int,
    val minFrameSize: Int,
    val maxFrameSize: Int,
    val md5Signature: ByteArray = ByteArray(16)
) {
    init {
        require(md5Signature.size == 16) { "STREAMINFO MD5 signature must be exactly 16 bytes." }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is FlacStreamInfo) {
            return false
        }

        return sampleRate == other.sampleRate &&
                channels == other.channels &&
                bitsPerSample == other.bitsPerSample &&
                totalSamples == other.totalSamples &&
                minBlockSize == other.minBlockSize &&
                maxBlockSize == other.maxBlockSize &&
                minFrameSize == other.minFrameSize &&
                maxFrameSize == other.maxFrameSize &&
                md5Signature.contentEquals(other.md5Signature)
    }

    override fun hashCode(): Int {
        var result = sampleRate
        result = 31 * result + channels
        result = 31 * result + bitsPerSample
        result = 31 * result + totalSamples.hashCode()
        result = 31 * result + minBlockSize
        result = 31 * result + maxBlockSize
        result = 31 * result + minFrameSize
        result = 31 * result + maxFrameSize
        result = 31 * result + md5Signature.contentHashCode()
        return result
    }
}

/**
 * Vorbis comment block after wrapper-level grouping.
 *
 * libFLAC exposes comments as repeated `KEY=value` entries. V1 groups them by
 * key while preserving the original possibility of multiple values.
 */
data class FlacVorbisComment(
    val vendor: String,
    val comments: Map<String, List<String>>
)

/**
 * Picture metadata block returned by libFLAC.
 *
 * `type` is kept as the original numeric enum value from libFLAC so the V1 API
 * does not need a second mapping layer.
 *
 * The numeric type follows the FLAC picture type registry. For example, `3`
 * is commonly used for front cover art. Use [FlacFormat.pictureViolation] when
 * accepting user-supplied pictures and you want libFLAC's exact legality
 * message before writing metadata.
 */
data class FlacPicture(
    val type: Int,
    val mimeType: String,
    val description: String,
    val width: Int,
    val height: Int,
    val depth: Int,
    val colors: Int,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FlacPicture

        if (type != other.type) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (depth != other.depth) return false
        if (colors != other.colors) return false
        if (mimeType != other.mimeType) return false
        if (description != other.description) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + depth
        result = 31 * result + colors
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * APPLICATION metadata block returned by libFLAC.
 *
 * The identifier is always four bytes in the FLAC bitstream. The wrapper keeps
 * it as bytes because application IDs are not guaranteed to be printable text.
 */
data class FlacApplicationBlock(
    val id: ByteArray,
    val data: ByteArray
) {
    init {
        require(id.size == 4) { "APPLICATION metadata ID must be exactly 4 bytes." }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FlacApplicationBlock

        if (!id.contentEquals(other.id)) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.contentHashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * One SEEKTABLE point.
 *
 * `sampleNumber` is the target sample frame, `streamOffset` is the byte offset
 * of the target frame from the first frame, and `frameSamples` is the number of
 * sample frames in that target frame.
 *
 * A `sampleNumber` of `-1` is the wrapper's Java/Kotlin representation of the
 * FLAC placeholder seek point. Other sample numbers and stream offsets must be
 * non-negative when written.
 */
data class FlacSeekPoint(
    val sampleNumber: Long,
    val streamOffset: Long,
    val frameSamples: Int
)

/** SEEKTABLE metadata block containing zero or more seek points. */
data class FlacSeekTable(
    val points: List<FlacSeekPoint>
)

/**
 * One CUESHEET index point.
 *
 * `offset` is relative to the containing track offset and `number` is the
 * original FLAC cue index number.
 *
 * FLAC stores cue index numbers in an 8-bit field, so values must fit
 * `0..255` when encoded.
 */
data class FlacCueSheetIndex(
    val offset: Long,
    val number: Int
)

/**
 * One CUESHEET track.
 *
 * `type` is kept as the raw FLAC bit value: 0 means audio, 1 means non-audio.
 * The lead-out track is exposed the same way libFLAC reports it.
 *
 * [isrc] is stored by FLAC as a fixed 12-byte printable ASCII field. Longer or
 * non-ASCII values are rejected by encoder metadata validation.
 */
data class FlacCueSheetTrack(
    val offset: Long,
    val number: Int,
    val isrc: String,
    val type: Int,
    val preEmphasis: Boolean,
    val indices: List<FlacCueSheetIndex>
)

/**
 * CUESHEET metadata block returned by libFLAC.
 *
 * The media catalog number and ISRC fields are trimmed at the first native NUL
 * byte because the FLAC structures store fixed-width, NUL-padded ASCII fields.
 *
 * [mediaCatalogNumber] is limited to the FLAC fixed 128-byte printable ASCII
 * field when writing metadata.
 */
data class FlacCueSheet(
    val mediaCatalogNumber: String,
    val leadIn: Long,
    val isCd: Boolean,
    val tracks: List<FlacCueSheetTrack>
)

/**
 * PADDING metadata block.
 *
 * FLAC stores padding as zero bytes with only a 24-bit metadata length field.
 * The wrapper therefore exposes the byte length, not an allocated payload.
 */
data class FlacPaddingBlock(
    val length: Int
) {
    init {
        require(length in 0..FLAC_METADATA_MAX_BLOCK_LENGTH) {
            "PADDING metadata length must fit the FLAC 24-bit metadata length field."
        }
    }
}

/**
 * Unknown metadata block preserved as opaque bytes.
 *
 * FLAC currently defines metadata type codes 0..6. Type codes 7..126 are
 * reserved for future block types and libFLAC exposes their payload as opaque
 * data so applications can preserve blocks they do not understand.
 */
class FlacUnknownMetadataBlock(
    val type: Int,
    data: ByteArray
) {
    val data: ByteArray = data.copyOf()

    init {
        require(type in FLAC_UNKNOWN_METADATA_MIN_TYPE..FLAC_UNKNOWN_METADATA_MAX_TYPE) {
            "Unknown metadata type must be in the FLAC reserved range 7..126."
        }
        require(this.data.size <= FLAC_METADATA_MAX_BLOCK_LENGTH) {
            "Unknown metadata payload length must fit the FLAC 24-bit metadata length field."
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is FlacUnknownMetadataBlock) {
            return false
        }

        return type == other.type && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        return 31 * type + data.contentHashCode()
    }

    override fun toString(): String {
        return "FlacUnknownMetadataBlock(type=$type, data=${data.contentToString()})"
    }
}

/**
 * One non-STREAMINFO metadata block in physical FLAC metadata order.
 *
 * The encoder owns STREAMINFO for newly written files, so this ordered model is
 * limited to the blocks callers may preserve or supply around encoded audio.
 */
sealed interface FlacMetadataBlock {
    data class VorbisComment(val comment: FlacVorbisComment) : FlacMetadataBlock

    data class Picture(val picture: FlacPicture) : FlacMetadataBlock

    data class Application(val application: FlacApplicationBlock) : FlacMetadataBlock

    data class SeekTable(val seekTable: FlacSeekTable) : FlacMetadataBlock

    data class CueSheet(val cueSheet: FlacCueSheet) : FlacMetadataBlock

    data class Padding(val padding: FlacPaddingBlock) : FlacMetadataBlock

    data class Unknown(val unknown: FlacUnknownMetadataBlock) : FlacMetadataBlock
}

/**
 * Aggregated read-only metadata view returned by [FlacMetadataReader].
 *
 * This read model covers the common safe-to-materialise metadata blocks. Raw
 * unknown blocks and padding are exposed so re-encode workflows can preserve
 * metadata the wrapper does not otherwise interpret. [blocks] preserves the
 * physical order of non-STREAMINFO metadata blocks for callers that need
 * exact-order round trips.
 */
data class FlacMetadata(
    val streamInfo: FlacStreamInfo,
    val vorbisComment: FlacVorbisComment?,
    val pictures: List<FlacPicture>,
    val applicationBlocks: List<FlacApplicationBlock> = emptyList(),
    val seekTables: List<FlacSeekTable> = emptyList(),
    val cueSheets: List<FlacCueSheet> = emptyList(),
    val paddingBlocks: List<FlacPaddingBlock> = emptyList(),
    val unknownBlocks: List<FlacUnknownMetadataBlock> = emptyList(),
    val blocks: List<FlacMetadataBlock> = emptyList()
)

/**
 * Fully materialised PCM output produced by [FlacDecoder.decode].
 *
 * Audio data is stored as one interleaved signed PCM array because that is the
 * exact layout emitted by the JNI bridge and libFLAC write callback.
 *
 * Memory use grows with `totalFrames * channels`. For long files, prefer the
 * streaming decode APIs so chunks can be processed and released one at a time.
 */
data class FlacDecodedAudio(
    val streamInfo: FlacStreamInfo,
    val interleavedSamples: IntArray,
    val totalFrames: Long
) {
    init {
        require(totalFrames >= 0L) { "Total frame count must be non-negative." }

        val expectedSampleCount = totalFrames * streamInfo.channels.toLong()
        require(expectedSampleCount <= Int.MAX_VALUE.toLong()) {
            "Decoded sample count is too large for one JVM IntArray."
        }
        require(interleavedSamples.size == expectedSampleCount.toInt()) {
            "Interleaved sample count must equal totalFrames * channels."
        }
    }

    /**
     * Splits the interleaved PCM stream into one array per channel.
     *
     * This helper is intentionally explicit about copying because many Java and
     * Kotlin callers prefer channel-major buffers for DSP or waveform work.
     */
    fun splitByChannel(): List<IntArray> {
        require(totalFrames <= Int.MAX_VALUE.toLong()) {
            "Channel split requires totalFrames to fit in one JVM IntArray length."
        }
        val result = List(streamInfo.channels) { IntArray(totalFrames.toInt()) }
        var sourceIndex = 0
        for (frameIndex in 0 until totalFrames.toInt()) {
            for (channelIndex in 0 until streamInfo.channels) {
                result[channelIndex][frameIndex] = interleavedSamples[sourceIndex++]
            }
        }
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FlacDecodedAudio

        if (totalFrames != other.totalFrames) return false
        if (streamInfo != other.streamInfo) return false
        if (!interleavedSamples.contentEquals(other.interleavedSamples)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = totalFrames.hashCode()
        result = 31 * result + streamInfo.hashCode()
        result = 31 * result + interleavedSamples.contentHashCode()
        return result
    }
}

/**
 * Consumer callback used by [FlacDecoder].
 *
 * The native decoder works frame-by-frame and this interface mirrors that
 * execution model while hiding JNI and pointer management from callers.
 */
interface PcmConsumer {
    /** Called once STREAMINFO is available and before any PCM frames arrive. */
    fun onStreamInfo(info: FlacStreamInfo)

    /**
     * Called for each decoded chunk of PCM data.
     *
     * `samples` are channel-interleaved signed PCM values stored in 32-bit
     * integers. The valid element count is always `frames * channels`.
     */
    fun onPcmInterleaved(samples: IntArray, frames: Int)

    /** Called after the decoder reaches end-of-stream successfully. */
    fun onComplete()
}

/**
 * Default collector used by the convenience decode API.
 *
 * The class is public so callers can reuse it when they want streaming decode
 * callbacks first and a materialised PCM result afterwards.
 *
 * The collector copies every incoming chunk and later merges those copies into
 * one array. That is simple and safe for small or medium clips, but large files
 * should normally use a custom [PcmConsumer] to avoid buffering the whole
 * decoded signal.
 */
class BufferingPcmConsumer @JvmOverloads constructor(
    private val firstFrameIndex: Long = 0L,
    private val maxFrames: Long? = null
) : PcmConsumer {
    private var streamInfo: FlacStreamInfo? = null
    private val chunks = ArrayList<IntArray>()
    private var totalFrames: Long = 0
    private var totalSamples: Long = 0
    private var completed: Boolean = false

    init {
        validateDecodeRange(firstFrameIndex, maxFrames)
    }

    constructor(firstFrameIndex: Long, maxFrames: Long) : this(firstFrameIndex, maxFrames as Long?)

    override fun onStreamInfo(info: FlacStreamInfo) {
        check(streamInfo == null) { "STREAMINFO was delivered more than once." }
        streamInfo = info
    }

    override fun onPcmInterleaved(samples: IntArray, frames: Int) {
        val currentInfo = checkNotNull(streamInfo) {
            "PCM data arrived before STREAMINFO was delivered."
        }
        check(!completed) { "PCM data arrived after decoding completed." }

        val expectedSampleCount = frames.toLong() * currentInfo.channels.toLong()
        require(frames >= 0) { "Frame count must be non-negative." }
        require(expectedSampleCount <= Int.MAX_VALUE.toLong()) {
            "PCM chunk is too large for one JVM IntArray."
        }
        require(samples.size == expectedSampleCount.toInt()) {
            "PCM chunk size must equal frames * channels."
        }

        chunks += samples.copyOf()
        totalFrames += frames.toLong()
        totalSamples += samples.size.toLong()
    }

    override fun onComplete() {
        completed = true
    }

    /**
     * Returns the fully buffered decode result after [onComplete].
     *
     * The method copies chunk data into one contiguous array so downstream code
     * gets a simple immutable-style result object instead of chunk bookkeeping.
     */
    fun toDecodedAudio(): FlacDecodedAudio {
        val currentInfo = checkNotNull(streamInfo) {
            "STREAMINFO was never delivered by the decoder."
        }
        check(completed) { "Decoding has not completed yet." }
        check(totalSamples <= Int.MAX_VALUE.toLong()) {
            "Decoded sample count is too large for one JVM IntArray."
        }

        val mergedSamples = IntArray(totalSamples.toInt())
        var destinationIndex = 0
        for (chunk in chunks) {
            chunk.copyInto(mergedSamples, destinationIndex)
            destinationIndex += chunk.size
        }

        expectedDecodedFrameCount(currentInfo, firstFrameIndex, maxFrames)?.let { expectedFrames ->
            check(expectedFrames == totalFrames) {
                "Decoded frame count $totalFrames does not match expected frame count $expectedFrames."
            }
        }

        return FlacDecodedAudio(
            streamInfo = currentInfo,
            interleavedSamples = mergedSamples,
            totalFrames = totalFrames
        )
    }

}

internal fun validateDecodeRange(firstSample: Long, maxFrames: Long?) {
    require(firstSample >= 0L) { "First sample must be non-negative." }
    require(maxFrames == null || maxFrames >= 0L) { "Max frames must be non-negative." }
    require(maxFrames == null || maxFrames <= Long.MAX_VALUE - firstSample) {
        "Decode range end must not overflow Long."
    }
}

internal fun expectedDecodedFrameCount(
    streamInfo: FlacStreamInfo,
    firstSample: Long,
    maxFrames: Long?
): Long? {
    if (streamInfo.totalSamples <= 0L) {
        return maxFrames
    }

    val availableFrames = streamInfo.totalSamples - firstSample
    check(availableFrames >= 0L) {
        "Decode range starts after STREAMINFO totalSamples."
    }
    return maxFrames?.let { minOf(it, availableFrames) } ?: availableFrames
}
