package org.zzvsjs.jflac

import org.zzvsjs.jflac.internal.NativeBindings
import org.zzvsjs.jflac.internal.toPublicMetadata
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.nio.file.Path
import kotlin.io.path.absolutePathString

/**
 * Synchronous decoder backed by libFLAC.
 *
 * File paths, sequential streams, and seekable channels share the same public
 * callback models while mapping to the libFLAC entry point that fits each
 * source.
 *
 * Choose the API shape by how much audio the caller wants to hold at once:
 *
 * - [decode] returns a fully materialised [FlacDecodedAudio] and is the
 *   simplest option for short files.
 * - [decodeInterleaved] and [decodeChannels] stream chunks synchronously into
 *   handlers, so callers can process large files without one huge PCM array.
 * - [open] creates a reusable seek-capable session for repeated range reads.
 * - [openPull] advances only when [FlacPullDecodingSession.readInterleaved] is
 *   called, which is useful for adapters such as Java Sound.
 *
 * Example:
 *
 * ```
 * val decoder = FlacDecoder()
 * decoder.decodeInterleaved(Path.of("song.flac")) { chunk ->
 *     println("frames from ${chunk.firstFrameIndex}: ${chunk.frames}")
 * }
 * ```
 *
 * Limitations and exceptions:
 *
 * - Plain [InputStream] decode is sequential. Use a file or
 *   [SeekableByteChannel] when range decoding or repeated seeks are required.
 * - `firstSample` is a zero-based frame index, not a raw sample-array index.
 *   `maxFrames` is also counted in frames, where one frame contains one sample
 *   for every channel.
 * - Invalid ranges throw [IllegalArgumentException]. A valid range that starts
 *   after the known end of the stream throws [FlacDecodeException].
 * - Native loading, malformed FLAC data, read failures, and libFLAC callback
 *   failures surface as [NativeLoadException], [FlacDecodeException], or the
 *   original Java I/O/runtime exception where one is already pending.
 */
class FlacDecoder {
    /**
     * Opens a reusable file-based decode session.
     *
     * The returned session owns native decoder state and must be closed by the
     * caller. Use Kotlin `use { ... }` or Java try-with-resources.
     */
    fun open(path: Path): FlacDecodingSession {
        val inspected = inspectNativeFlacPath(path)
        FlacNativeLoader.load()
        val streamInfo = NativeBindings.readMetadata(
            inspected.path.absolutePathString(),
            inspected.container.nativeCode
        )
            .toPublicMetadata()
            .streamInfo
        val handle = NativeBindings.openDecoderFile(inspected.path.absolutePathString(), inspected.container.nativeCode)
        if (handle == 0L) {
            throw FlacDecodeException("Native decoder initialization returned an invalid handle without throwing an exception.")
        }
        return NativeFlacDecodingSession(handle, streamInfo)
    }

    /**
     * Opens a reusable decode session for a seekable FLAC or Ogg FLAC channel.
     *
     * The channel is not closed by the returned session. libFLAC byte offsets
     * are relative to the channel position at open time.
     */
    fun open(input: SeekableByteChannel): FlacDecodingSession {
        FlacNativeLoader.load()
        val container = inspectNativeFlacChannel(input)
        val streamInfo = readChannelStreamInfo(input, container)
        val handle = NativeBindings.openDecoderChannel(input, container.nativeCode)
        if (handle == 0L) {
            throw FlacDecodeException("Native channel decoder initialization returned an invalid handle without throwing an exception.")
        }
        return NativeFlacDecodingSession(handle, streamInfo)
    }

    /**
     * Opens a pull-based file decoder.
     *
     * The returned session advances libFLAC only when [FlacPullDecodingSession.readInterleaved]
     * is called. This is intended for adapters that need back-pressure from a
     * consumer API instead of eager whole-file decoding.
     */
    fun openPull(path: Path): FlacPullDecodingSession {
        val inspected = inspectNativeFlacPath(path)
        FlacNativeLoader.load()
        val result = NativeBindings.openPullDecoderFile(
            inspected.path.absolutePathString(),
            inspected.container.nativeCode
        )
        return NativeFlacPullDecodingSession(result.handle, result.streamInfo)
    }

    /**
     * Opens a pull-based decoder over a sequential FLAC or Ogg FLAC stream.
     *
     * The stream is not closed by the returned session. The native session
     * keeps a global reference to the inspected stream wrapper until
     * [FlacPullDecodingSession.close].
     */
    fun openPull(input: InputStream): FlacPullDecodingSession {
        FlacNativeLoader.load()
        val inspected = inspectNativeFlacStream(input)
        val result = NativeBindings.openPullDecoderStream(inspected.input, inspected.container.nativeCode)
        return NativeFlacPullDecodingSession(result.handle, result.streamInfo)
    }

    /**
     * Opens a pull-based decoder over a seekable FLAC or Ogg FLAC channel.
     *
     * The channel is not closed by the returned session. libFLAC byte offsets
     * remain relative to the channel position captured at open time.
     */
    fun openPull(input: SeekableByteChannel): FlacPullDecodingSession {
        FlacNativeLoader.load()
        val container = inspectNativeFlacChannel(input)
        val result = NativeBindings.openPullDecoderChannel(input, container.nativeCode)
        return NativeFlacPullDecodingSession(result.handle, result.streamInfo)
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
     * Decodes a FLAC or Ogg FLAC stream into one JVM-owned PCM buffer.
     *
     * Plain streams are sequential-only in V1. Use file or seekable-channel
     * APIs when the caller needs range decode or a reusable seekable session.
     */
    fun decode(input: InputStream): FlacDecodedAudio {
        val consumer = BufferingPcmConsumer()
        decode(input, consumer)
        return consumer.toDecodedAudio()
    }

    /**
     * Decodes a seekable FLAC or Ogg FLAC channel into one JVM-owned PCM buffer.
     */
    fun decode(input: SeekableByteChannel): FlacDecodedAudio {
        val consumer = BufferingPcmConsumer()
        decode(input, consumer)
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
     * Seeks to [firstSample] and decodes at most [maxFrames] PCM frames from a
     * seekable FLAC or Ogg FLAC channel into one JVM-owned buffer.
     */
    fun decode(input: SeekableByteChannel, firstSample: Long, maxFrames: Long): FlacDecodedAudio {
        val consumer = BufferingPcmConsumer(firstSample, maxFrames)
        decode(input, firstSample, maxFrames, consumer)
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
     * Decodes a FLAC or Ogg FLAC stream and forwards interleaved PCM chunks.
     */
    @JvmOverloads
    fun decodeInterleaved(
        input: InputStream,
        onChunk: FlacInterleavedPcmHandler,
        onStreamInfo: FlacStreamInfoHandler? = null,
        onComplete: FlacDecodeCompleteHandler? = null
    ): FlacDecodeSummary {
        val consumer = InterleavedForwardingPcmConsumer(
            onStreamInfo = onStreamInfo,
            onChunk = onChunk,
            onComplete = onComplete
        )
        decode(input, consumer)
        return consumer.summary()
    }

    /**
     * Decodes a seekable FLAC or Ogg FLAC channel and forwards interleaved PCM
     * chunks.
     */
    @JvmOverloads
    fun decodeInterleaved(
        input: SeekableByteChannel,
        onChunk: FlacInterleavedPcmHandler,
        onStreamInfo: FlacStreamInfoHandler? = null,
        onComplete: FlacDecodeCompleteHandler? = null
    ): FlacDecodeSummary {
        val consumer = InterleavedForwardingPcmConsumer(
            onStreamInfo = onStreamInfo,
            onChunk = onChunk,
            onComplete = onComplete
        )
        decode(input, consumer)
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
     * Seeks to [firstSample] on a seekable channel and decodes from there to
     * end-of-stream.
     */
    @JvmOverloads
    fun decodeInterleaved(
        input: SeekableByteChannel,
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
        decode(input, firstSample, consumer)
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
     * Seeks to [firstSample] on a seekable channel and decodes at most
     * [maxFrames] interleaved PCM frames.
     */
    @JvmOverloads
    fun decodeInterleaved(
        input: SeekableByteChannel,
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
        decode(input, firstSample, maxFrames, consumer)
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
     * Decodes a FLAC or Ogg FLAC stream and forwards channel-separated PCM chunks.
     */
    @JvmOverloads
    fun decodeChannels(
        input: InputStream,
        onChunk: FlacChannelPcmHandler,
        onStreamInfo: FlacStreamInfoHandler? = null,
        onComplete: FlacDecodeCompleteHandler? = null
    ): FlacDecodeSummary {
        val consumer = ChannelForwardingPcmConsumer(
            onStreamInfo = onStreamInfo,
            onChunk = onChunk,
            onComplete = onComplete
        )
        decode(input, consumer)
        return consumer.summary()
    }

    /**
     * Decodes a seekable FLAC or Ogg FLAC channel and forwards channel-separated
     * PCM chunks.
     */
    @JvmOverloads
    fun decodeChannels(
        input: SeekableByteChannel,
        onChunk: FlacChannelPcmHandler,
        onStreamInfo: FlacStreamInfoHandler? = null,
        onComplete: FlacDecodeCompleteHandler? = null
    ): FlacDecodeSummary {
        val consumer = ChannelForwardingPcmConsumer(
            onStreamInfo = onStreamInfo,
            onChunk = onChunk,
            onComplete = onComplete
        )
        decode(input, consumer)
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
     * Seeks to [firstSample] on a seekable channel and forwards
     * channel-separated PCM chunks.
     */
    @JvmOverloads
    fun decodeChannels(
        input: SeekableByteChannel,
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
        decode(input, firstSample, consumer)
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
     * Seeks to [firstSample] on a seekable channel and decodes at most
     * [maxFrames] channel-separated PCM frames.
     */
    @JvmOverloads
    fun decodeChannels(
        input: SeekableByteChannel,
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
        decode(input, firstSample, maxFrames, consumer)
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
     * Decodes a FLAC or Ogg FLAC stream into the richer streaming listener API.
     */
    fun decode(input: InputStream, listener: FlacDecodeListener): FlacDecodeSummary {
        val consumer = InterleavedForwardingPcmConsumer(
            onStreamInfo = FlacStreamInfoHandler { info -> listener.onStreamInfo(info) },
            onChunk = FlacInterleavedPcmHandler { chunk -> listener.onInterleavedPcm(chunk) },
            onComplete = FlacDecodeCompleteHandler { summary -> listener.onComplete(summary) }
        )
        decode(input, consumer)
        return consumer.summary()
    }

    /**
     * Decodes a seekable FLAC or Ogg FLAC channel into the richer streaming
     * listener API.
     */
    fun decode(input: SeekableByteChannel, listener: FlacDecodeListener): FlacDecodeSummary {
        val consumer = InterleavedForwardingPcmConsumer(
            onStreamInfo = FlacStreamInfoHandler { info -> listener.onStreamInfo(info) },
            onChunk = FlacInterleavedPcmHandler { chunk -> listener.onInterleavedPcm(chunk) },
            onComplete = FlacDecodeCompleteHandler { summary -> listener.onComplete(summary) }
        )
        decode(input, consumer)
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
     * Seeks to [firstSample] on a seekable channel and decodes into the
     * interleaved listener API.
     */
    fun decode(input: SeekableByteChannel, firstSample: Long, listener: FlacDecodeListener): FlacDecodeSummary {
        val consumer = InterleavedForwardingPcmConsumer(
            firstFrameIndex = firstSample,
            onStreamInfo = FlacStreamInfoHandler { info -> listener.onStreamInfo(info) },
            onChunk = FlacInterleavedPcmHandler { chunk -> listener.onInterleavedPcm(chunk) },
            onComplete = FlacDecodeCompleteHandler { summary -> listener.onComplete(summary) }
        )
        decode(input, firstSample, consumer)
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
     * Seeks to [firstSample] on a seekable channel and decodes at most
     * [maxFrames] into the interleaved listener API.
     */
    fun decode(
        input: SeekableByteChannel,
        firstSample: Long,
        maxFrames: Long,
        listener: FlacDecodeListener
    ): FlacDecodeSummary {
        val consumer = InterleavedForwardingPcmConsumer(
            firstFrameIndex = firstSample,
            maxFrames = maxFrames,
            onStreamInfo = FlacStreamInfoHandler { info -> listener.onStreamInfo(info) },
            onChunk = FlacInterleavedPcmHandler { chunk -> listener.onInterleavedPcm(chunk) },
            onComplete = FlacDecodeCompleteHandler { summary -> listener.onComplete(summary) }
        )
        decode(input, firstSample, maxFrames, consumer)
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
     * Decodes a FLAC or Ogg FLAC stream into the channel-oriented listener API.
     */
    fun decode(input: InputStream, listener: FlacChannelDecodeListener): FlacDecodeSummary {
        val consumer = ChannelForwardingPcmConsumer(
            onStreamInfo = FlacStreamInfoHandler { info -> listener.onStreamInfo(info) },
            onChunk = FlacChannelPcmHandler { chunk -> listener.onChannelPcm(chunk) },
            onComplete = FlacDecodeCompleteHandler { summary -> listener.onComplete(summary) }
        )
        decode(input, consumer)
        return consumer.summary()
    }

    /**
     * Decodes a seekable FLAC or Ogg FLAC channel into the channel-oriented
     * listener API.
     */
    fun decode(input: SeekableByteChannel, listener: FlacChannelDecodeListener): FlacDecodeSummary {
        val consumer = ChannelForwardingPcmConsumer(
            onStreamInfo = FlacStreamInfoHandler { info -> listener.onStreamInfo(info) },
            onChunk = FlacChannelPcmHandler { chunk -> listener.onChannelPcm(chunk) },
            onComplete = FlacDecodeCompleteHandler { summary -> listener.onComplete(summary) }
        )
        decode(input, consumer)
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
     * Seeks to [firstSample] on a seekable channel and decodes into the
     * channel listener API.
     */
    fun decode(input: SeekableByteChannel, firstSample: Long, listener: FlacChannelDecodeListener): FlacDecodeSummary {
        val consumer = ChannelForwardingPcmConsumer(
            firstFrameIndex = firstSample,
            onStreamInfo = FlacStreamInfoHandler { info -> listener.onStreamInfo(info) },
            onChunk = FlacChannelPcmHandler { chunk -> listener.onChannelPcm(chunk) },
            onComplete = FlacDecodeCompleteHandler { summary -> listener.onComplete(summary) }
        )
        decode(input, firstSample, consumer)
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
     * Seeks to [firstSample] on a seekable channel and decodes at most
     * [maxFrames] into the channel listener API.
     */
    fun decode(
        input: SeekableByteChannel,
        firstSample: Long,
        maxFrames: Long,
        listener: FlacChannelDecodeListener
    ): FlacDecodeSummary {
        val consumer = ChannelForwardingPcmConsumer(
            firstFrameIndex = firstSample,
            maxFrames = maxFrames,
            onStreamInfo = FlacStreamInfoHandler { info -> listener.onStreamInfo(info) },
            onChunk = FlacChannelPcmHandler { chunk -> listener.onChannelPcm(chunk) },
            onComplete = FlacDecodeCompleteHandler { summary -> listener.onComplete(summary) }
        )
        decode(input, firstSample, maxFrames, consumer)
        return consumer.summary()
    }

    /**
     * Decodes a FLAC or Ogg FLAC file and streams PCM data into [consumer].
     *
     * The call blocks until decoding completes or throws an exception.
     */
    fun decode(path: Path, consumer: PcmConsumer) {
        val inspected = inspectNativeFlacPath(path)
        FlacNativeLoader.load()
        NativeBindings.decodeFile(inspected.path.absolutePathString(), inspected.container.nativeCode, consumer)
    }

    /**
     * Decodes a FLAC or Ogg FLAC stream and streams PCM data into [consumer].
     *
     * The stream is read sequentially and is not closed by this method.
     */
    fun decode(input: InputStream, consumer: PcmConsumer) {
        FlacNativeLoader.load()
        val inspected = inspectNativeFlacStream(input)
        NativeBindings.decodeStream(inspected.input, inspected.container.nativeCode, consumer)
    }

    /**
     * Decodes a seekable FLAC or Ogg FLAC channel and streams PCM data into
     * [consumer].
     *
     * The channel is not closed by this method. libFLAC byte offsets are
     * relative to the channel position when the call starts.
     */
    fun decode(input: SeekableByteChannel, consumer: PcmConsumer) {
        FlacNativeLoader.load()
        val container = inspectNativeFlacChannel(input)
        NativeBindings.decodeChannel(input, container.nativeCode, consumer)
    }

    /**
     * Seeks to [firstSample], then streams PCM data into [consumer].
     */
    fun decode(path: Path, firstSample: Long, consumer: PcmConsumer) {
        require(firstSample >= 0L) { "First sample must be non-negative." }
        val inspected = inspectNativeFlacPath(path)
        FlacNativeLoader.load()
        NativeBindings.decodeFileFrom(
            inspected.path.absolutePathString(),
            inspected.container.nativeCode,
            firstSample,
            consumer
        )
    }

    /**
     * Seeks to [firstSample] on a seekable channel, then streams PCM data into
     * [consumer].
     */
    fun decode(input: SeekableByteChannel, firstSample: Long, consumer: PcmConsumer) {
        require(firstSample >= 0L) { "First sample must be non-negative." }
        FlacNativeLoader.load()
        val container = inspectNativeFlacChannel(input)
        NativeBindings.decodeChannelFrom(input, container.nativeCode, firstSample, consumer)
    }

    /**
     * Seeks to [firstSample], then streams at most [maxFrames] PCM frames into
     * [consumer].
     */
    fun decode(path: Path, firstSample: Long, maxFrames: Long, consumer: PcmConsumer) {
        validateDecodeRange(firstSample, maxFrames)
        val inspected = inspectNativeFlacPath(path)
        FlacNativeLoader.load()
        NativeBindings.decodeFileRange(
            inspected.path.absolutePathString(),
            inspected.container.nativeCode,
            firstSample,
            maxFrames,
            consumer
        )
    }

    /**
     * Seeks to [firstSample] on a seekable channel, then streams at most
     * [maxFrames] PCM frames into [consumer].
     */
    fun decode(input: SeekableByteChannel, firstSample: Long, maxFrames: Long, consumer: PcmConsumer) {
        validateDecodeRange(firstSample, maxFrames)
        FlacNativeLoader.load()
        val container = inspectNativeFlacChannel(input)
        NativeBindings.decodeChannelRange(input, container.nativeCode, firstSample, maxFrames, consumer)
    }

    private fun readChannelStreamInfo(input: SeekableByteChannel, container: NativeFlacContainer): FlacStreamInfo {
        val originalPosition = input.position()
        val consumer = BufferingPcmConsumer(firstFrameIndex = 0, maxFrames = 0)
        try {
            NativeBindings.decodeChannelRange(input, container.nativeCode, 0, 0, consumer)
            return consumer.toDecodedAudio().streamInfo
        } finally {
            input.position(originalPosition)
        }
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
 * JVM lifecycle wrapper for one native pull decoder handle.
 *
 * The native side owns libFLAC state and any long-lived stream/channel global
 * references. This wrapper serialises reads and close so Java never asks the
 * native decoder to advance while another thread is releasing the same handle.
 */
internal class NativeFlacPullDecodingSession(
    initialHandle: Long,
    override val streamInfo: FlacStreamInfo
) : FlacPullDecodingSession {
    private val lock = Any()
    private var handle: Long = initialHandle

    override fun readInterleaved(interleavedSamples: IntArray, maxFrames: Int): Int {
        return synchronized(lock) {
            val currentHandle = handle
            check(currentHandle != 0L) { "The FLAC pull decoding session is no longer active." }

            require(maxFrames >= 0) { "Max frames must be non-negative." }

            val requiredSamples = maxFrames.toLong() * streamInfo.channels.toLong()
            require(requiredSamples <= Int.MAX_VALUE.toLong()) {
                "Interleaved sample buffer must fit maxFrames * channels."
            }
            require(interleavedSamples.size >= requiredSamples.toInt()) {
                "Interleaved sample buffer must fit maxFrames * channels."
            }

            if (maxFrames == 0) {
                return@synchronized 0
            }

            try {
                NativeBindings.readPullDecoderInterleaved(currentHandle, interleavedSamples, maxFrames)
            } catch (t: Throwable) {
                /*
                 * A native/source failure can leave libFLAC in an aborted or
                 * otherwise unreusable decoder state. Treat that the same way
                 * reusable callback decode does: remove the Java-visible handle
                 * immediately so later reads fail at the session boundary, and
                 * release the native owner while preserving the original error.
                 */
                handle = 0L
                try {
                    NativeBindings.releasePullDecoder(currentHandle)
                } catch (releaseFailure: Throwable) {
                    t.addSuppressed(releaseFailure)
                }
                throw t
            }
        }
    }

    override fun close() {
        val currentHandle = synchronized(lock) {
            val activeHandle = handle
            handle = 0L
            activeHandle
        }

        if (currentHandle != 0L) {
            NativeBindings.releasePullDecoder(currentHandle)
        }
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
