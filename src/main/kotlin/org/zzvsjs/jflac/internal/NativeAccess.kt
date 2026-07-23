package org.zzvsjs.jflac.internal

import org.zzvsjs.jflac.FlacAudioFormat
import org.zzvsjs.jflac.FlacEncodingMetadata
import org.zzvsjs.jflac.FlacEncodingOptions
import org.zzvsjs.jflac.FlacMetadata
import org.zzvsjs.jflac.FlacMetadataBlock
import org.zzvsjs.jflac.FlacPicture
import org.zzvsjs.jflac.FlacStreamInfo
import org.zzvsjs.jflac.PcmConsumer
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel

private const val FLAC_METADATA_TYPE_PADDING = 1
private const val FLAC_METADATA_TYPE_APPLICATION = 2
private const val FLAC_METADATA_TYPE_SEEKTABLE = 3
private const val FLAC_METADATA_TYPE_VORBIS_COMMENT = 4
private const val FLAC_METADATA_TYPE_CUESHEET = 5
private const val FLAC_METADATA_TYPE_PICTURE = 6

/**
 * Kotlin-module façade over package-private JNI declarations.
 *
 * Every method is JVM-synthetic: Kotlin implementation code in this module can
 * call it, while Java source consumers cannot treat the JNI bridge as supported
 * API. Signatures deliberately expose only public wrapper/JDK types, never the
 * package-private transport DTOs used by native reflection.
 */
internal object NativeAccess {
    @JvmSynthetic
    fun verifyRuntime() = NativeBindings.verifyRuntime()

    @JvmSynthetic
    fun readMetadata(path: String, container: Int): FlacMetadata =
        NativeBindings.readMetadata(path, container).toPublicMetadata()

    @JvmSynthetic
    fun decodeFile(
        path: String,
        container: Int,
        checkMd5: Boolean,
        decodeChainedOgg: Boolean,
        consumer: PcmConsumer
    ) = NativeBindings.decodeFile(path, container, checkMd5, decodeChainedOgg, consumer)

    @JvmSynthetic
    fun decodeStream(
        input: InputStream,
        container: Int,
        checkMd5: Boolean,
        decodeChainedOgg: Boolean,
        consumer: PcmConsumer
    ) = NativeBindings.decodeStream(input, container, checkMd5, decodeChainedOgg, consumer)

    @JvmSynthetic
    fun decodeChannel(
        input: SeekableByteChannel,
        container: Int,
        checkMd5: Boolean,
        decodeChainedOgg: Boolean,
        consumer: PcmConsumer
    ) = NativeBindings.decodeChannel(input, container, checkMd5, decodeChainedOgg, consumer)

    @JvmSynthetic
    fun decodeFileFrom(path: String, container: Int, firstSample: Long, consumer: PcmConsumer) =
        NativeBindings.decodeFileFrom(path, container, firstSample, consumer)

    @JvmSynthetic
    fun decodeChannelFrom(
        input: SeekableByteChannel,
        container: Int,
        firstSample: Long,
        consumer: PcmConsumer
    ) = NativeBindings.decodeChannelFrom(input, container, firstSample, consumer)

    @JvmSynthetic
    fun decodeFileRange(
        path: String,
        container: Int,
        firstSample: Long,
        maxFrames: Long,
        consumer: PcmConsumer
    ) = NativeBindings.decodeFileRange(path, container, firstSample, maxFrames, consumer)

    @JvmSynthetic
    fun decodeChannelRange(
        input: SeekableByteChannel,
        container: Int,
        firstSample: Long,
        maxFrames: Long,
        consumer: PcmConsumer
    ) = NativeBindings.decodeChannelRange(input, container, firstSample, maxFrames, consumer)

    @JvmSynthetic
    fun openDecoderFile(path: String, container: Int): Long = NativeBindings.openDecoderFile(path, container)

    @JvmSynthetic
    fun openDecoderChannel(input: SeekableByteChannel, container: Int): Long =
        NativeBindings.openDecoderChannel(input, container)

    @JvmSynthetic
    fun <T> openPullDecoderFile(
        path: String,
        container: Int,
        factory: (Long, FlacStreamInfo) -> T
    ): T {
        val result = NativeBindings.openPullDecoderFile(path, container)
        return result.createSession(factory)
    }

    @JvmSynthetic
    fun <T> openPullDecoderStream(
        input: InputStream,
        container: Int,
        factory: (Long, FlacStreamInfo) -> T
    ): T {
        val result = NativeBindings.openPullDecoderStream(input, container)
        return result.createSession(factory)
    }

    @JvmSynthetic
    fun <T> openPullDecoderChannel(
        input: SeekableByteChannel,
        container: Int,
        factory: (Long, FlacStreamInfo) -> T
    ): T {
        val result = NativeBindings.openPullDecoderChannel(input, container)
        return result.createSession(factory)
    }

    @JvmSynthetic
    fun decodeDecoderFrom(handle: Long, firstSample: Long, consumer: PcmConsumer) =
        NativeBindings.decodeDecoderFrom(handle, firstSample, consumer)

    @JvmSynthetic
    fun decodeDecoderRange(handle: Long, firstSample: Long, maxFrames: Long, consumer: PcmConsumer) =
        NativeBindings.decodeDecoderRange(handle, firstSample, maxFrames, consumer)

    @JvmSynthetic
    fun readPullDecoderInterleaved(handle: Long, samples: IntArray, maxFrames: Int): Int =
        NativeBindings.readPullDecoderInterleaved(handle, samples, maxFrames)

    @JvmSynthetic
    fun releaseDecoder(handle: Long) = NativeBindings.releaseDecoder(handle)

    @JvmSynthetic
    fun releasePullDecoder(handle: Long) = NativeBindings.releasePullDecoder(handle)

    @JvmSynthetic
    fun openEncoderFile(
        path: String,
        format: FlacAudioFormat,
        metadata: FlacEncodingMetadata,
        options: FlacEncodingOptions
    ): Long = NativeBindings.openEncoderFile(path, encodingRequest(format, metadata, options))

    @JvmSynthetic
    fun openEncoderStream(
        output: OutputStream,
        format: FlacAudioFormat,
        metadata: FlacEncodingMetadata,
        options: FlacEncodingOptions
    ): Long = NativeBindings.openEncoderStream(output, encodingRequest(format, metadata, options))

    @JvmSynthetic
    fun openEncoderChannel(
        output: SeekableByteChannel,
        format: FlacAudioFormat,
        metadata: FlacEncodingMetadata,
        options: FlacEncodingOptions
    ): Long = NativeBindings.openEncoderChannel(output, encodingRequest(format, metadata, options))

    @JvmSynthetic
    fun writeMetadata(
        path: String,
        metadata: FlacEncodingMetadata,
        usePadding: Boolean,
        preserveFileStats: Boolean
    ) = NativeBindings.writeMetadata(path, metadataEditRequest(metadata), usePadding, preserveFileStats)

    @JvmSynthetic
    fun writeEncoderInterleaved(handle: Long, samples: IntArray, frames: Int) =
        NativeBindings.writeEncoderInterleaved(handle, samples, frames)

    @JvmSynthetic
    fun finishEncoder(handle: Long) = NativeBindings.finishEncoder(handle)

    @JvmSynthetic
    fun releaseEncoder(handle: Long) = NativeBindings.releaseEncoder(handle)

    @JvmSynthetic
    fun isSampleRateValid(sampleRate: Int): Boolean = NativeBindings.isSampleRateValid(sampleRate)

    @JvmSynthetic
    fun isSampleRateSubset(sampleRate: Int): Boolean = NativeBindings.isSampleRateSubset(sampleRate)

    @JvmSynthetic
    fun isBlockSizeSubset(blockSize: Int, sampleRate: Int): Boolean =
        NativeBindings.isBlockSizeSubset(blockSize, sampleRate)

    @JvmSynthetic
    fun isVorbisCommentNameLegal(name: String): Boolean = NativeBindings.isVorbisCommentNameLegal(name)

    @JvmSynthetic
    fun isVorbisCommentValueLegal(value: String): Boolean = NativeBindings.isVorbisCommentValueLegal(value)

    @JvmSynthetic
    fun isVorbisCommentEntryLegal(entry: String): Boolean = NativeBindings.isVorbisCommentEntryLegal(entry)

    @JvmSynthetic
    fun pictureViolation(picture: FlacPicture): String? = NativeBindings.pictureViolation(picture)

    private fun <T> NativePullDecoderOpenResult.createSession(
        factory: (Long, FlacStreamInfo) -> T
    ): T {
        val openedHandle = handle
        try {
            return factory(openedHandle, streamInfo)
        } catch (t: Throwable) {
            try {
                NativeBindings.releasePullDecoder(openedHandle)
            } catch (releaseFailure: Throwable) {
                if (releaseFailure !== t) {
                    t.addSuppressed(releaseFailure)
                }
            }

            throw t
        }
    }

    private fun encodingRequest(
        format: FlacAudioFormat,
        metadata: FlacEncodingMetadata,
        options: FlacEncodingOptions
    ): NativeEncodingRequest {
        val commentEntries = metadata.comments.toVorbisCommentEntries().toTypedArray()
        val metadataBlockTypes = metadata.blocks.map(FlacMetadataBlock::nativeType).toIntArray()
        val metadataBlockValues = metadata.blocks.map(FlacMetadataBlock::nativeValue).toTypedArray()
        return NativeEncodingRequest(
            format.sampleRate,
            format.channels,
            format.bitsPerSample,
            format.totalSamplesEstimate,
            options.compressionLevel,
            options.verify,
            options.streamableSubset,
            options.blockSize,
            options.container.nativeCode,
            options.oggSerialNumber,
            commentEntries,
            metadata.pictures.toTypedArray(),
            metadata.applicationBlocks.toTypedArray(),
            metadata.seekTables.toTypedArray(),
            metadata.cueSheets.toTypedArray(),
            metadata.paddingBlocks.toTypedArray(),
            metadata.unknownBlocks.toTypedArray(),
            metadataBlockTypes,
            metadataBlockValues,
            options.numThreads
        )
    }

    private fun metadataEditRequest(metadata: FlacEncodingMetadata): NativeMetadataEditRequest {
        val metadataBlockTypes = metadata.blocks.map(FlacMetadataBlock::nativeType).toIntArray()
        val metadataBlockValues = metadata.blocks.map(FlacMetadataBlock::nativeValue).toTypedArray()
        return NativeMetadataEditRequest(
            metadata.comments.toVorbisCommentEntries().toTypedArray(),
            metadata.pictures.toTypedArray(),
            metadata.applicationBlocks.toTypedArray(),
            metadata.seekTables.toTypedArray(),
            metadata.cueSheets.toTypedArray(),
            metadata.paddingBlocks.toTypedArray(),
            metadata.unknownBlocks.toTypedArray(),
            metadataBlockTypes,
            metadataBlockValues
        )
    }
}

private fun Map<String, List<String>>.toVorbisCommentEntries(): List<String> = entries.flatMap { (key, values) ->
    values.map { value -> "$key=$value" }
}

private fun FlacMetadataBlock.nativeType(): Int = when (this) {
    is FlacMetadataBlock.VorbisComment -> FLAC_METADATA_TYPE_VORBIS_COMMENT
    is FlacMetadataBlock.Picture -> FLAC_METADATA_TYPE_PICTURE
    is FlacMetadataBlock.Application -> FLAC_METADATA_TYPE_APPLICATION
    is FlacMetadataBlock.SeekTable -> FLAC_METADATA_TYPE_SEEKTABLE
    is FlacMetadataBlock.CueSheet -> FLAC_METADATA_TYPE_CUESHEET
    is FlacMetadataBlock.Padding -> FLAC_METADATA_TYPE_PADDING
    is FlacMetadataBlock.Unknown -> unknown.type
}

private fun FlacMetadataBlock.nativeValue(): Any = when (this) {
    is FlacMetadataBlock.VorbisComment -> NativeVorbisCommentBlock(
        comment.vendor,
        comment.comments.toVorbisCommentEntries().toTypedArray()
    )
    is FlacMetadataBlock.Picture -> picture
    is FlacMetadataBlock.Application -> application
    is FlacMetadataBlock.SeekTable -> seekTable
    is FlacMetadataBlock.CueSheet -> cueSheet
    is FlacMetadataBlock.Padding -> padding
    is FlacMetadataBlock.Unknown -> unknown
}
