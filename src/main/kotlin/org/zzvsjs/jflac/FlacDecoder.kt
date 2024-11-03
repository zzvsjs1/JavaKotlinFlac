package org.zzvsjs.jflac

import org.zzvsjs.jflac.internal.NativeBindings
import org.zzvsjs.jflac.internal.toPublicMetadata
import java.nio.file.Path
import kotlin.io.path.absolutePathString

/**
 * Synchronous file-based decoder backed by libFLAC.
 *
 * Path validation and native library loading are handled on the JVM side so
 * decode failures stay predictable before entering JNI.
 */
class FlacDecoder {
    /**
     * Opens a reusable file-based decode session.
     *
     * The returned session owns native decoder state and must be closed by the
     * caller. Use Kotlin `use { ... }` or Java try-with-resources.
     */
    fun open(path: Path): FlacDecodingSession {
        val normalizedPath = validateNativeFlacPath(path)
        FlacNativeLoader.load()
        val streamInfo = NativeBindings.readMetadata(normalizedPath.absolutePathString())
            .toPublicMetadata()
            .streamInfo
        val handle = NativeBindings.openDecoderFile(normalizedPath.absolutePathString())
        if (handle == 0L) {
            throw FlacDecodeException("Native decoder initialization returned an invalid handle without throwing an exception.")
        }
        return NativeFlacDecodingSession(handle, streamInfo)
    }

    /**
     * Decodes the whole FLAC file into one JVM-owned PCM buffer.
     *
     * This overload is the simplest entry point for callers that want the
     * entire decoded signal in memory after the native decode finishes.
     */
    fun decode(path: Path): FlacDecodedAudio {
        val consumer = BufferingPcmConsumer()
        decode(path, consumer)
        return consumer.toDecodedAudio()
    }

    /**
     * Seeks to [firstSample] and decodes at most [maxFrames] PCM frames into
     * one JVM-owned buffer.
     */
    fun decode(path: Path, firstSample: Long, maxFrames: Long): FlacDecodedAudio {
        val consumer = BufferingPcmConsumer(firstSample, maxFrames)
        decode(path, firstSample, maxFrames, consumer)
        return consumer.toDecodedAudio()
    }

    /**
     * Decodes a FLAC file and forwards streaming interleaved PCM chunks.
     *
     * This overload is useful when the caller wants chunked processing but
     * still prefers higher-level wrapper models over the low-level JNI
     * [PcmConsumer] interface.
     */
    @JvmOverloads
    fun decodeInterleaved(
        path: Path,
        onChunk: FlacInterleavedPcmHandler,
        onStreamInfo: FlacStreamInfoHandler? = null,
        onComplete: FlacDecodeCompleteHandler? = null
    ): FlacDecodeSummary {
        val consumer = InterleavedForwardingPcmConsumer(
            onStreamInfo = onStreamInfo,
            onChunk = onChunk,
            onComplete = onComplete
        )
        decode(path, consumer)
        return consumer.summary()
    }

    /**
     * Seeks to [firstSample] and decodes from there to end-of-stream.
     *
     * Chunk frame positions stay absolute to the original stream, so a seek to
     * sample 1000 produces a first chunk whose `firstFrameIndex` is 1000.
     */
    @JvmOverloads
    fun decodeInterleaved(
        path: Path,
        firstSample: Long,
        onChunk: FlacInterleavedPcmHandler,
        onStreamInfo: FlacStreamInfoHandler? = null,
        onComplete: FlacDecodeCompleteHandler? = null
    ): FlacDecodeSummary {
        val consumer = InterleavedForwardingPcmConsumer(
            firstFrameIndex = firstSample,
            onStreamInfo = onStreamInfo,
            onChunk = onChunk,
            onComplete = onComplete
        )
        decode(path, firstSample, consumer)
        return consumer.summary()
    }

    /**
     * Seeks to [firstSample] and decodes at most [maxFrames] interleaved PCM
     * frames.
     *
     * Chunk frame positions stay absolute to the original stream.
     */
    @JvmOverloads
    fun decodeInterleaved(
        path: Path,
        firstSample: Long,
        maxFrames: Long,
        onChunk: FlacInterleavedPcmHandler,
        onStreamInfo: FlacStreamInfoHandler? = null,
        onComplete: FlacDecodeCompleteHandler? = null
    ): FlacDecodeSummary {
        val consumer = InterleavedForwardingPcmConsumer(
            firstFrameIndex = firstSample,
            maxFrames = maxFrames,
            onStreamInfo = onStreamInfo,
            onChunk = onChunk,
            onComplete = onComplete
        )
        decode(path, firstSample, maxFrames, consumer)
        return consumer.summary()
    }

    /**
     * Decodes a FLAC file and forwards channel-separated PCM chunks.
     *
     * Channel separation adds one copy per chunk but produces a layout that is
     * often easier to consume from DSP or waveform-oriented code.
     */
    @JvmOverloads
    fun decodeChannels(
        path: Path,
        onChunk: FlacChannelPcmHandler,
        onStreamInfo: FlacStreamInfoHandler? = null,
        onComplete: FlacDecodeCompleteHandler? = null
    ): FlacDecodeSummary {
        val consumer = ChannelForwardingPcmConsumer(
            onStreamInfo = onStreamInfo,
            onChunk = onChunk,
            onComplete = onComplete
        )
        decode(path, consumer)
        return consumer.summary()
    }

    /**
     * Seeks to [firstSample] and forwards channel-separated PCM chunks.
     */
    @JvmOverloads
    fun decodeChannels(
        path: Path,
        firstSample: Long,
        onChunk: FlacChannelPcmHandler,
        onStreamInfo: FlacStreamInfoHandler? = null,
        onComplete: FlacDecodeCompleteHandler? = null
    ): FlacDecodeSummary {
        val consumer = ChannelForwardingPcmConsumer(
            firstFrameIndex = firstSample,
            onStreamInfo = onStreamInfo,
            onChunk = onChunk,
            onComplete = onComplete
        )
        decode(path, firstSample, consumer)
        return consumer.summary()
    }

    /**
     * Seeks to [firstSample] and decodes at most [maxFrames] channel-separated
     * PCM frames.
     */
    @JvmOverloads
    fun decodeChannels(
        path: Path,
        firstSample: Long,
        maxFrames: Long,
        onChunk: FlacChannelPcmHandler,
        onStreamInfo: FlacStreamInfoHandler? = null,
        onComplete: FlacDecodeCompleteHandler? = null
    ): FlacDecodeSummary {
        val consumer = ChannelForwardingPcmConsumer(
            firstFrameIndex = firstSample,
            maxFrames = maxFrames,
            onStreamInfo = onStreamInfo,
            onChunk = onChunk,
            onComplete = onComplete
        )
        decode(path, firstSample, maxFrames, consumer)
        return consumer.summary()
    }

    /**
     * Decodes a FLAC file into the richer streaming listener interface.
     *
     * This overload keeps the public API close to libFLAC's callback model
     * while still presenting JVM-friendly models and lifecycle summaries.
     */
    fun decode(path: Path, listener: FlacDecodeListener): FlacDecodeSummary {
        val consumer = InterleavedForwardingPcmConsumer(
            onStreamInfo = FlacStreamInfoHandler { info -> listener.onStreamInfo(info) },
            onChunk = FlacInterleavedPcmHandler { chunk -> listener.onInterleavedPcm(chunk) },
            onComplete = FlacDecodeCompleteHandler { summary -> listener.onComplete(summary) }
        )
        decode(path, consumer)
        return consumer.summary()
    }

    /**
     * Seeks to [firstSample] and decodes into the interleaved listener API.
     */
    fun decode(path: Path, firstSample: Long, listener: FlacDecodeListener): FlacDecodeSummary {
        val consumer = InterleavedForwardingPcmConsumer(
            firstFrameIndex = firstSample,
            onStreamInfo = FlacStreamInfoHandler { info -> listener.onStreamInfo(info) },
            onChunk = FlacInterleavedPcmHandler { chunk -> listener.onInterleavedPcm(chunk) },
            onComplete = FlacDecodeCompleteHandler { summary -> listener.onComplete(summary) }
        )
        decode(path, firstSample, consumer)
        return consumer.summary()
    }

    /**
     * Seeks to [firstSample] and decodes at most [maxFrames] into the
     * interleaved listener API.
     */
    fun decode(path: Path, firstSample: Long, maxFrames: Long, listener: FlacDecodeListener): FlacDecodeSummary {
        val consumer = InterleavedForwardingPcmConsumer(
            firstFrameIndex = firstSample,
            maxFrames = maxFrames,
            onStreamInfo = FlacStreamInfoHandler { info -> listener.onStreamInfo(info) },
            onChunk = FlacInterleavedPcmHandler { chunk -> listener.onInterleavedPcm(chunk) },
            onComplete = FlacDecodeCompleteHandler { summary -> listener.onComplete(summary) }
        )
        decode(path, firstSample, maxFrames, consumer)
        return consumer.summary()
    }

    /**
     * Decodes a FLAC file into the channel-oriented streaming listener.
     *
     * This is the most convenient decode path for callers that never want to
     * deal with interleaved PCM directly.
     */
    fun decode(path: Path, listener: FlacChannelDecodeListener): FlacDecodeSummary {
        val consumer = ChannelForwardingPcmConsumer(
            onStreamInfo = FlacStreamInfoHandler { info -> listener.onStreamInfo(info) },
            onChunk = FlacChannelPcmHandler { chunk -> listener.onChannelPcm(chunk) },
            onComplete = FlacDecodeCompleteHandler { summary -> listener.onComplete(summary) }
        )
        decode(path, consumer)
        return consumer.summary()
    }

    /**
     * Seeks to [firstSample] and decodes into the channel listener API.
     */
    fun decode(path: Path, firstSample: Long, listener: FlacChannelDecodeListener): FlacDecodeSummary {
        val consumer = ChannelForwardingPcmConsumer(
            firstFrameIndex = firstSample,
            onStreamInfo = FlacStreamInfoHandler { info -> listener.onStreamInfo(info) },
            onChunk = FlacChannelPcmHandler { chunk -> listener.onChannelPcm(chunk) },
            onComplete = FlacDecodeCompleteHandler { summary -> listener.onComplete(summary) }
        )
        decode(path, firstSample, consumer)
        return consumer.summary()
    }

    /**
     * Seeks to [firstSample] and decodes at most [maxFrames] into the channel
     * listener API.
     */
    fun decode(path: Path, firstSample: Long, maxFrames: Long, listener: FlacChannelDecodeListener): FlacDecodeSummary {
        val consumer = ChannelForwardingPcmConsumer(
            firstFrameIndex = firstSample,
            maxFrames = maxFrames,
            onStreamInfo = FlacStreamInfoHandler { info -> listener.onStreamInfo(info) },
            onChunk = FlacChannelPcmHandler { chunk -> listener.onChannelPcm(chunk) },
            onComplete = FlacDecodeCompleteHandler { summary -> listener.onComplete(summary) }
        )
        decode(path, firstSample, maxFrames, consumer)
        return consumer.summary()
    }

    /**
     * Decodes a native FLAC file and streams PCM data into [consumer].
     *
     * The call blocks until decoding completes or throws an exception.
     */
    fun decode(path: Path, consumer: PcmConsumer) {
        val normalizedPath = validateNativeFlacPath(path)
        FlacNativeLoader.load()
        NativeBindings.decodeFile(normalizedPath.absolutePathString(), consumer)
    }

    /**
     * Seeks to [firstSample], then streams PCM data into [consumer].
     */
    fun decode(path: Path, firstSample: Long, consumer: PcmConsumer) {
        require(firstSample >= 0L) { "First sample must be non-negative." }
        val normalizedPath = validateNativeFlacPath(path)
        FlacNativeLoader.load()
        NativeBindings.decodeFileFrom(normalizedPath.absolutePathString(), firstSample, consumer)
    }

    /**
     * Seeks to [firstSample], then streams at most [maxFrames] PCM frames into
     * [consumer].
     */
    fun decode(path: Path, firstSample: Long, maxFrames: Long, consumer: PcmConsumer) {
        validateDecodeRange(firstSample, maxFrames)
        val normalizedPath = validateNativeFlacPath(path)
        FlacNativeLoader.load()
        NativeBindings.decodeFileRange(normalizedPath.absolutePathString(), firstSample, maxFrames, consumer)
    }
}

/**
 * JVM-owned wrapper around one reusable native decoder handle.
 */
internal class NativeFlacDecodingSession(
    initialHandle: Long,
    override val streamInfo: FlacStreamInfo
) : FlacDecodingSession {
    private val lock = Any()
    private var handle: Long = initialHandle
    private var state: SessionState = SessionState.ACTIVE
    private var closeRequested: Boolean = false

    override fun decodeInterleaved(
        firstSample: Long,
        onChunk: FlacInterleavedPcmHandler,
        onStreamInfo: FlacStreamInfoHandler?,
        onComplete: FlacDecodeCompleteHandler?
    ): FlacDecodeSummary {
        return decodeInterleaved(
            firstSample = firstSample,
            maxFrames = null,
            onChunk = onChunk,
            onStreamInfo = onStreamInfo,
            onComplete = onComplete
        )
    }

    override fun decodeInterleaved(
        firstSample: Long,
        maxFrames: Long,
        onChunk: FlacInterleavedPcmHandler,
        onStreamInfo: FlacStreamInfoHandler?,
        onComplete: FlacDecodeCompleteHandler?
    ): FlacDecodeSummary {
        return decodeInterleaved(
            firstSample = firstSample,
            maxFrames = maxFrames as Long?,
            onChunk = onChunk,
            onStreamInfo = onStreamInfo,
            onComplete = onComplete
        )
    }

    private fun decodeInterleaved(
        firstSample: Long,
        maxFrames: Long?,
        onChunk: FlacInterleavedPcmHandler,
        onStreamInfo: FlacStreamInfoHandler?,
        onComplete: FlacDecodeCompleteHandler?
    ): FlacDecodeSummary {
        val consumer = InterleavedForwardingPcmConsumer(
            firstFrameIndex = firstSample,
            maxFrames = maxFrames,
            onStreamInfo = onStreamInfo,
            onChunk = onChunk,
            onComplete = onComplete
        )
        decode(firstSample, maxFrames, consumer)
        return consumer.summary()
    }

    override fun decodeChannels(
        firstSample: Long,
        onChunk: FlacChannelPcmHandler,
        onStreamInfo: FlacStreamInfoHandler?,
        onComplete: FlacDecodeCompleteHandler?
    ): FlacDecodeSummary {
        return decodeChannels(
            firstSample = firstSample,
            maxFrames = null,
            onChunk = onChunk,
            onStreamInfo = onStreamInfo,
            onComplete = onComplete
        )
    }

    override fun decodeChannels(
        firstSample: Long,
        maxFrames: Long,
        onChunk: FlacChannelPcmHandler,
        onStreamInfo: FlacStreamInfoHandler?,
        onComplete: FlacDecodeCompleteHandler?
    ): FlacDecodeSummary {
        return decodeChannels(
            firstSample = firstSample,
            maxFrames = maxFrames as Long?,
            onChunk = onChunk,
            onStreamInfo = onStreamInfo,
            onComplete = onComplete
        )
    }

    private fun decodeChannels(
        firstSample: Long,
        maxFrames: Long?,
        onChunk: FlacChannelPcmHandler,
        onStreamInfo: FlacStreamInfoHandler?,
        onComplete: FlacDecodeCompleteHandler?
    ): FlacDecodeSummary {
        val consumer = ChannelForwardingPcmConsumer(
            firstFrameIndex = firstSample,
            maxFrames = maxFrames,
            onStreamInfo = onStreamInfo,
            onChunk = onChunk,
            onComplete = onComplete
        )
        decode(firstSample, maxFrames, consumer)
        return consumer.summary()
    }

    override fun close() {
        val currentHandle = synchronized(lock) {
            when (state) {
                SessionState.ACTIVE -> {
                    val activeHandle = handle
                    handle = 0L
                    state = SessionState.CLOSED
                    activeHandle
                }
                SessionState.DECODING -> {
                    closeRequested = true
                    0L
                }
                SessionState.CLOSED,
                SessionState.FAILED -> 0L
            }
        }

        if (currentHandle != 0L) {
            NativeBindings.releaseDecoder(currentHandle)
        }
    }

    private fun decode(firstSample: Long, maxFrames: Long?, consumer: PcmConsumer) {
        validateDecodeRange(firstSample, maxFrames)

        val currentHandle = beginDecode()
        var succeeded = false
        try {
            consumer.onStreamInfo(streamInfo)
            if (maxFrames == null) {
                NativeBindings.decodeDecoderFrom(currentHandle, firstSample, consumer)
            } else {
                NativeBindings.decodeDecoderRange(currentHandle, firstSample, maxFrames, consumer)
            }
            succeeded = true
        } catch (t: Throwable) {
            releaseAfterFailure()
            throw t
        } finally {
            if (succeeded) {
                finishDecode()
            }
        }
    }

    private fun beginDecode(): Long {
        return synchronized(lock) {
            if (state != SessionState.ACTIVE || handle == 0L) {
                throw IllegalStateException("The FLAC decoding session is no longer active.")
            }

            state = SessionState.DECODING
            handle
        }
    }

    private fun finishDecode() {
        val currentHandle = synchronized(lock) {
            if (state != SessionState.DECODING) {
                0L
            } else if (closeRequested) {
                val activeHandle = handle
                handle = 0L
                closeRequested = false
                state = SessionState.CLOSED
                activeHandle
            } else {
                state = SessionState.ACTIVE
                0L
            }
        }

        if (currentHandle != 0L) {
            NativeBindings.releaseDecoder(currentHandle)
        }
    }

    private fun releaseAfterFailure() {
        val currentHandle = synchronized(lock) {
            if (state == SessionState.CLOSED || state == SessionState.FAILED || handle == 0L) {
                closeRequested = false
                0L
            } else {
                val activeHandle = handle
                handle = 0L
                closeRequested = false
                state = SessionState.FAILED
                activeHandle
            }
        }

        if (currentHandle != 0L) {
            NativeBindings.releaseDecoder(currentHandle)
        }
    }

    private enum class SessionState {
        ACTIVE,
        DECODING,
        CLOSED,
        FAILED
    }
}

/**
 * Shared decode bookkeeping for the richer JVM-side streaming adapters.
 *
 * The JNI layer still speaks [PcmConsumer], so these adapters translate the
 * low-level callback flow into higher-level wrapper chunk models.
 */
private abstract class SummaryTrackingPcmConsumer(
    private val firstFrameIndex: Long = 0L,
    private val maxFrames: Long? = null
) : PcmConsumer {
    protected var streamInfo: FlacStreamInfo? = null
    protected var totalFrames: Long = 0
    private var completed: Boolean = false

    init {
        validateDecodeRange(firstFrameIndex, maxFrames)
    }

    final override fun onStreamInfo(info: FlacStreamInfo) {
        check(streamInfo == null) { "STREAMINFO was delivered more than once." }
        streamInfo = info
        handleStreamInfo(info)
    }

    final override fun onPcmInterleaved(samples: IntArray, frames: Int) {
        val currentInfo = checkNotNull(streamInfo) {
            "PCM data arrived before STREAMINFO was delivered."
        }
        check(!completed) { "PCM data arrived after decoding completed." }

        val chunk = FlacInterleavedPcmChunk(
            streamInfo = currentInfo,
            interleavedSamples = samples.copyOf(),
            frames = frames,
            firstFrameIndex = Math.addExact(firstFrameIndex, totalFrames)
        )
        totalFrames = Math.addExact(totalFrames, frames.toLong())
        handleChunk(chunk)
    }

    final override fun onComplete() {
        completed = true
        handleComplete(summary())
    }

    fun summary(): FlacDecodeSummary {
        val currentInfo = checkNotNull(streamInfo) {
            "STREAMINFO was never delivered by the decoder."
        }
        check(completed) { "Decoding has not completed yet." }

        expectedDecodedFrameCount(currentInfo, firstFrameIndex, maxFrames)?.let { expectedFrames ->
            check(expectedFrames == totalFrames) {
                "Decoded frame count $totalFrames does not match expected frame count $expectedFrames."
            }
        }

        return FlacDecodeSummary(
            streamInfo = currentInfo,
            totalFrames = totalFrames
        )
    }

    protected abstract fun handleStreamInfo(info: FlacStreamInfo)

    protected abstract fun handleChunk(chunk: FlacInterleavedPcmChunk)

    protected abstract fun handleComplete(summary: FlacDecodeSummary)
}

/** Forwards interleaved chunks into handler-based public decode APIs. */
private class InterleavedForwardingPcmConsumer(
    firstFrameIndex: Long = 0L,
    maxFrames: Long? = null,
    private val onStreamInfo: FlacStreamInfoHandler?,
    private val onChunk: FlacInterleavedPcmHandler,
    private val onComplete: FlacDecodeCompleteHandler?
) : SummaryTrackingPcmConsumer(firstFrameIndex, maxFrames) {
    override fun handleStreamInfo(info: FlacStreamInfo) {
        onStreamInfo?.onStreamInfo(info)
    }

    override fun handleChunk(chunk: FlacInterleavedPcmChunk) {
        onChunk.onChunk(chunk)
    }

    override fun handleComplete(summary: FlacDecodeSummary) {
        onComplete?.onComplete(summary)
    }
}

/** Converts interleaved native chunks into per-channel public decode chunks. */
private class ChannelForwardingPcmConsumer(
    firstFrameIndex: Long = 0L,
    maxFrames: Long? = null,
    private val onStreamInfo: FlacStreamInfoHandler?,
    private val onChunk: FlacChannelPcmHandler,
    private val onComplete: FlacDecodeCompleteHandler?
) : SummaryTrackingPcmConsumer(firstFrameIndex, maxFrames) {
    override fun handleStreamInfo(info: FlacStreamInfo) {
        onStreamInfo?.onStreamInfo(info)
    }

    override fun handleChunk(chunk: FlacInterleavedPcmChunk) {
        onChunk.onChunk(chunk.toChannelChunk())
    }

    override fun handleComplete(summary: FlacDecodeSummary) {
        onComplete?.onComplete(summary)
    }
}
