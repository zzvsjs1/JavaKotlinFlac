package org.zzvsjs.jflac

/**
 * Optional checks applied by [FlacDecoder].
 *
 * @property checkMd5 asks libFLAC to compare the PCM decoded from a complete
 *   stream with the non-zero MD5 signature stored in STREAMINFO. libFLAC
 *   cannot perform this check after seeking, and a pull session may be closed
 *   before physical end-of-stream, so this option is deliberately limited to
 *   whole-stream `decode` calls. Streams without a stored MD5 signature still
 *   decode successfully because there is no signature to compare.
 * @property decodeChainedOgg decodes every link in a chained Ogg FLAC source.
 *   This first wrapper iteration accepts whole-stream decode only and requires
 *   every link to use the same sample rate, channel count, and bit depth. The
 *   single reported [FlacStreamInfo] keeps those common format fields but uses
 *   unknown aggregate sizes and an all-zero MD5 signature.
 */
data class FlacDecodingOptions @JvmOverloads constructor(
    val checkMd5: Boolean = false,
    val decodeChainedOgg: Boolean = false
)

/**
 * Lightweight summary returned by streaming decode entry points.
 *
 * The summary gives callers the final stream description and decoded frame
 * count without forcing them to buffer all PCM samples in memory.
 */
data class FlacDecodeSummary(
    val streamInfo: FlacStreamInfo,
    val totalFrames: Long
) {
    init {
        require(totalFrames >= 0L) { "Total frame count must be non-negative." }
    }

    /** Total number of interleaved PCM values emitted by the decoder. */
    val totalSamples: Long
        get() = Math.multiplyExact(totalFrames, streamInfo.channels.toLong())
}

/**
 * One interleaved PCM chunk emitted during streaming decode.
 *
 * `firstFrameIndex` is the zero-based index of the first frame in this chunk
 * relative to the entire decoded stream.
 */
data class FlacInterleavedPcmChunk(
    val streamInfo: FlacStreamInfo,
    val interleavedSamples: IntArray,
    val frames: Int,
    val firstFrameIndex: Long
) {
    init {
        require(frames >= 0) { "Frame count must be non-negative." }

        require(firstFrameIndex >= 0L) { "First frame index must be non-negative." }

        val expectedSampleCount = frames.toLong() * streamInfo.channels.toLong()
        require(expectedSampleCount <= Int.MAX_VALUE.toLong()) {
            "PCM chunk is too large for one JVM IntArray."
        }

        require(interleavedSamples.size == expectedSampleCount.toInt()) {
            "Interleaved sample count must equal frames * channels."
        }
    }

    /**
     * Converts this interleaved chunk into one array per channel.
     *
     * The conversion copies data because the output layout differs from the
     * native callback layout delivered by libFLAC.
     */
    fun splitByChannel(): List<IntArray> {
        val result = List(streamInfo.channels) { IntArray(frames) }
        var sourceIndex = 0
        for (frameIndex in 0 until frames) {
            for (channelIndex in 0 until streamInfo.channels) {
                result[channelIndex][frameIndex] = interleavedSamples[sourceIndex++]
            }
        }

        return result
    }
}

/**
 * One channel-separated PCM chunk emitted during streaming decode.
 *
 * Each list element represents exactly one channel and every channel array must
 * contain [frames] samples.
 */
data class FlacChannelPcmChunk(
    val streamInfo: FlacStreamInfo,
    val channelSamples: List<IntArray>,
    val frames: Int,
    val firstFrameIndex: Long
) {
    init {
        require(frames >= 0) { "Frame count must be non-negative." }

        require(firstFrameIndex >= 0L) { "First frame index must be non-negative." }

        require(channelSamples.size == streamInfo.channels) {
            "Channel sample list size must equal the FLAC channel count."
        }

        channelSamples.forEach { samples ->
            require(samples.size == frames) {
                "Each channel sample array must contain exactly 'frames' samples."
            }
        }
    }
}

/** Receives STREAMINFO as soon as libFLAC exposes it. */
fun interface FlacStreamInfoHandler {
    fun onStreamInfo(info: FlacStreamInfo)
}

/** Receives one chunk of interleaved PCM data during decode. */
fun interface FlacInterleavedPcmHandler {
    fun onChunk(chunk: FlacInterleavedPcmChunk)
}

/** Receives one chunk of channel-separated PCM data during decode. */
fun interface FlacChannelPcmHandler {
    fun onChunk(chunk: FlacChannelPcmChunk)
}

/** Receives the final decode summary after end-of-stream. */
fun interface FlacDecodeCompleteHandler {
    fun onComplete(summary: FlacDecodeSummary)
}

/**
 * Rich listener that maps the native decode callback flow into one interface.
 *
 * This is intended for callers who want streaming PCM plus lifecycle events
 * without working directly with the low-level [PcmConsumer] JNI contract.
 */
interface FlacDecodeListener {
    fun onStreamInfo(info: FlacStreamInfo)

    fun onInterleavedPcm(chunk: FlacInterleavedPcmChunk)

    fun onComplete(summary: FlacDecodeSummary)
}

/**
 * Convenience adapter with no-op implementations for [FlacDecodeListener].
 *
 * Java callers can subclass this and override only the callbacks they need.
 */
open class FlacDecodeAdapter : FlacDecodeListener {
    override fun onStreamInfo(info: FlacStreamInfo) = Unit

    override fun onInterleavedPcm(chunk: FlacInterleavedPcmChunk) = Unit

    override fun onComplete(summary: FlacDecodeSummary) = Unit
}

/**
 * Channel-oriented listener for callers that prefer de-interleaved PCM.
 *
 * This interface trades one extra copy per chunk for a friendlier shape for
 * DSP, visualisation, and per-channel analysis pipelines.
 */
interface FlacChannelDecodeListener {
    fun onStreamInfo(info: FlacStreamInfo)

    fun onChannelPcm(chunk: FlacChannelPcmChunk)

    fun onComplete(summary: FlacDecodeSummary)
}

/**
 * Convenience adapter with no-op implementations for [FlacChannelDecodeListener].
 */
open class FlacChannelDecodeAdapter : FlacChannelDecodeListener {
    override fun onStreamInfo(info: FlacStreamInfo) = Unit

    override fun onChannelPcm(chunk: FlacChannelPcmChunk) = Unit

    override fun onComplete(summary: FlacDecodeSummary) = Unit
}

/**
 * Reusable decoder session for repeated seek-decode operations.
 *
 * A session owns one native decoder handle. It is useful when callers need to
 * inspect several ranges from the same source without reopening native state
 * for each request.
 *
 * Example:
 *
 * ```
 * FlacDecoder().open(Path.of("song.flac")).use { session ->
 *     session.decodeInterleaved(firstSample = 0, maxFrames = 4096) { chunk ->
 *         println(chunk.frames)
 *     }
 *     session.decodeChannels(firstSample = 48_000) { chunk ->
 *         println(chunk.channelSamples.size)
 *     }
 * }
 * ```
 *
 * Sessions serialise native decoder use. They are intended for repeated calls
 * from one owner, not for concurrent decode calls from multiple threads.
 */
interface FlacDecodingSession : AutoCloseable {
    /** STREAMINFO for the opened FLAC source. */
    val streamInfo: FlacStreamInfo

    /**
     * Seeks to [firstSample] and forwards interleaved PCM chunks to [onChunk].
     *
     * [firstSample] is zero-based and counted in frames. Handler callbacks run
     * synchronously on the calling thread before this method returns.
     */
    fun decodeInterleaved(
        firstSample: Long,
        onChunk: FlacInterleavedPcmHandler,
        onStreamInfo: FlacStreamInfoHandler? = null,
        onComplete: FlacDecodeCompleteHandler? = null
    ): FlacDecodeSummary

    /**
     * Seeks to [firstSample] and forwards at most [maxFrames] interleaved PCM
     * frames.
     *
     * [maxFrames] is a frame count. For stereo, `maxFrames = 100` permits up to
     * 200 integer sample values in emitted interleaved chunks.
     */
    fun decodeInterleaved(
        firstSample: Long,
        maxFrames: Long,
        onChunk: FlacInterleavedPcmHandler,
        onStreamInfo: FlacStreamInfoHandler? = null,
        onComplete: FlacDecodeCompleteHandler? = null
    ): FlacDecodeSummary

    /**
     * Seeks to [firstSample] and forwards channel-separated PCM chunks.
     *
     * Channel separation copies each native interleaved chunk into one array per
     * channel, which is easier for per-channel processing but uses extra memory
     * for each chunk.
     */
    fun decodeChannels(
        firstSample: Long,
        onChunk: FlacChannelPcmHandler,
        onStreamInfo: FlacStreamInfoHandler? = null,
        onComplete: FlacDecodeCompleteHandler? = null
    ): FlacDecodeSummary

    /**
     * Seeks to [firstSample] and forwards at most [maxFrames] channel-separated
     * PCM frames.
     *
     * Invalid negative ranges throw [IllegalArgumentException]; native decode
     * or source failures throw [FlacDecodeException] or the original Java
     * exception when a callback already raised one.
     */
    fun decodeChannels(
        firstSample: Long,
        maxFrames: Long,
        onChunk: FlacChannelPcmHandler,
        onStreamInfo: FlacStreamInfoHandler? = null,
        onComplete: FlacDecodeCompleteHandler? = null
    ): FlacDecodeSummary

    /** Releases the native decoder handle. */
    override fun close()
}

/** Converts an interleaved chunk into a channel-separated chunk. */
internal fun FlacInterleavedPcmChunk.toChannelChunk(): FlacChannelPcmChunk {
    return FlacChannelPcmChunk(
        streamInfo = streamInfo,
        channelSamples = splitByChannel(),
        frames = frames,
        firstFrameIndex = firstFrameIndex
    )
}
