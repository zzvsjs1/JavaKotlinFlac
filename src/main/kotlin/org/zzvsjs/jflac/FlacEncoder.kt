package org.zzvsjs.jflac

import org.zzvsjs.jflac.internal.NativeBindings
import org.zzvsjs.jflac.internal.NativeEncodingRequest
import org.zzvsjs.jflac.internal.NativeVorbisCommentBlock
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString

/*
 * The write model uses -1 for the FLAC seekpoint placeholder because Java and
 * Kotlin Long cannot represent the unsigned all-bits-set sample number.
 */
private const val FLAC_SEEKPOINT_PLACEHOLDER_SAMPLE_NUMBER = -1L

/* FLAC stores seekpoint frame_samples in a 16-bit unsigned field. */
private const val FLAC_SEEKPOINT_MAX_FRAME_SAMPLES = 65_535

/* FLAC CUESHEET stores these text fields as fixed ASCII byte arrays. */
private const val FLAC_CUESHEET_MAX_FIXED_FIELD_BYTES = 128
private const val FLAC_CUESHEET_MAX_ISRC_BYTES = 12

/* FLAC CUESHEET track and index counts are stored in unsigned 8-bit fields. */
private const val FLAC_CUESHEET_MAX_LIST_ITEMS = 255

/* Every FLAC metadata block body length is stored in a 24-bit unsigned field. */
private const val FLAC_METADATA_MAX_BLOCK_LENGTH = 0xFF_FFFF

/* PICTURE has eight 32-bit scalar fields around its MIME, description and image bytes. */
private const val FLAC_PICTURE_FIXED_FIELD_BYTES = 32

/*
 * FLAC metadata block type codes from the bitstream specification. STREAMINFO
 * uses type 0 and is intentionally absent here because libFLAC owns it for new
 * encoder streams.
 */
private const val FLAC_METADATA_TYPE_PADDING = 1
private const val FLAC_METADATA_TYPE_APPLICATION = 2
private const val FLAC_METADATA_TYPE_SEEKTABLE = 3
private const val FLAC_METADATA_TYPE_VORBIS_COMMENT = 4
private const val FLAC_METADATA_TYPE_CUESHEET = 5
private const val FLAC_METADATA_TYPE_PICTURE = 6

/**
 * FLAC encoder backed by libFLAC.
 *
 * The public surface mirrors the existing decoder philosophy:
 * - JVM code validates caller input before crossing into JNI
 * - the native layer owns all libFLAC objects and file lifecycle details
 * - callers only work with small Kotlin/Java models and a session handle
 *
 * Example:
 *
 * ```
 * val format = FlacAudioFormat(sampleRate = 48_000, channels = 2, bitsPerSample = 16)
 * val samples = IntArray(48_000 * 2) { index -> if (index % 2 == 0) 1_000 else -1_000 }
 *
 * FlacEncoder().encode(
 *     output = Path.of("tone.flac"),
 *     format = format,
 *     samples = samples
 * )
 * ```
 *
 * For streaming writes, keep the returned [FlacEncodingSession] in `use { ... }`
 * or call [FlacEncodingSession.finish] yourself. The session finalises the
 * native encoder and is required even when all PCM chunks have already been
 * submitted.
 *
 * Limitations and exceptions:
 *
 * - [OutputStream] and [SeekableByteChannel] overloads do not close the caller's
 *   object. Only the native encoder lifecycle is closed.
 * - Sequential [OutputStream] output cannot seek back to patch final STREAMINFO
 *   statistics. File and seekable-channel output can.
 * - Invalid format, option, metadata, or PCM values throw
 *   [IllegalArgumentException]. Writing after a terminal session state throws
 *   [IllegalStateException].
 * - Native load failures throw [NativeLoadException]; libFLAC configuration,
 *   write, or finalise failures throw [FlacEncodeException].
 */
class FlacEncoder {
    /**
     * Opens a streaming encoder session for a FLAC or Ogg FLAC output file.
     *
     * The returned session accepts multiple PCM chunks. The caller is
     * responsible for finishing the session when all frames have been written.
     * File output is the most complete path because libFLAC owns a seekable
     * file handle and can write the final STREAMINFO values during finish.
     */
    @JvmOverloads
    fun open(
        output: Path,
        format: FlacAudioFormat,
        metadata: FlacEncodingMetadata = FlacEncodingMetadata(),
        options: FlacEncodingOptions = FlacEncodingOptions()
    ): FlacEncodingSession {
        val normalizedOutput = validateNativeFlacOutputPath(output)
        validateFlacAudioFormat(format)
        validateFlacEncodingOptions(options)
        validateFlacEncodingMetadata(metadata)

        FlacNativeLoader.load()
        val handle = NativeBindings.openEncoderFile(
            normalizedOutput.absolutePathString(),
            metadata.toNativeRequest(format, options)
        )
        if (handle == 0L) {
            throw FlacEncodeException(
                "Native encoder initialization returned an invalid handle without throwing an exception."
            )
        }
        return NativeFlacEncodingSession(handle, format)
    }

    /**
     * Opens a streaming encoder session for a sequential FLAC or Ogg FLAC
     * output stream.
     *
     * The stream is not closed by the returned session. V1 does not provide
     * seek/tell callbacks, so libFLAC cannot back-patch final STREAMINFO
     * statistics on this path.
     *
     * Use this overload when the destination is naturally sequential, for
     * example an HTTP response or archive entry. Use the file or channel
     * overload when final total-sample and MD5 statistics must be present in
     * STREAMINFO.
     */
    @JvmOverloads
    fun open(
        output: OutputStream,
        format: FlacAudioFormat,
        metadata: FlacEncodingMetadata = FlacEncodingMetadata(),
        options: FlacEncodingOptions = FlacEncodingOptions()
    ): FlacEncodingSession {
        validateFlacAudioFormat(format)
        validateFlacEncodingOptions(options)
        validateFlacEncodingMetadata(metadata)

        FlacNativeLoader.load()
        val handle = NativeBindings.openEncoderStream(
            output,
            metadata.toNativeRequest(format, options)
        )
        if (handle == 0L) {
            throw FlacEncodeException(
                "Native stream encoder initialization returned an invalid handle without throwing an exception."
            )
        }
        return NativeFlacEncodingSession(handle, format)
    }

    /**
     * Opens a streaming encoder session for a seekable FLAC or Ogg FLAC output
     * channel.
     *
     * The channel is not closed by the returned session. libFLAC byte offsets
     * are relative to the channel position at open time, and seek/tell
     * callbacks let libFLAC back-patch final STREAMINFO statistics.
     *
     * This is useful when the caller owns a larger container file and wants to
     * write FLAC data at the channel's current position.
     */
    @JvmOverloads
    fun open(
        output: SeekableByteChannel,
        format: FlacAudioFormat,
        metadata: FlacEncodingMetadata = FlacEncodingMetadata(),
        options: FlacEncodingOptions = FlacEncodingOptions()
    ): FlacEncodingSession {
        validateFlacAudioFormat(format)
        validateFlacEncodingOptions(options)
        validateFlacEncodingMetadata(metadata)

        FlacNativeLoader.load()
        val handle = NativeBindings.openEncoderChannel(
            output,
            metadata.toNativeRequest(format, options)
        )
        if (handle == 0L) {
            throw FlacEncodeException(
                "Native channel encoder initialization returned an invalid handle without throwing an exception."
            )
        }
        return NativeFlacEncodingSession(handle, format)
    }

    /**
     * Encodes one interleaved PCM buffer into a complete FLAC or Ogg FLAC file.
     *
     * This is a convenience wrapper built on top of [open] so there is only one
     * native encoder lifecycle implementation to maintain.
     *
     * `samples.size` must be divisible by `format.channels`. For stereo, six
     * samples represent three frames: left0, right0, left1, right1, left2,
     * right2.
     */
    @JvmOverloads
    fun encode(
        output: Path,
        format: FlacAudioFormat,
        samples: IntArray,
        metadata: FlacEncodingMetadata = FlacEncodingMetadata(),
        options: FlacEncodingOptions = FlacEncodingOptions()
    ) {
        val frames = computeFrameCount(samples, format.channels)
        open(output, format, metadata, options).use { session ->
            session.writeInterleaved(samples, frames)
        }
    }

    /**
     * Encodes one interleaved PCM buffer into a sequential FLAC or Ogg FLAC
     * output stream.
     *
     * The supplied stream remains open after this method returns or throws.
     */
    @JvmOverloads
    fun encode(
        output: OutputStream,
        format: FlacAudioFormat,
        samples: IntArray,
        metadata: FlacEncodingMetadata = FlacEncodingMetadata(),
        options: FlacEncodingOptions = FlacEncodingOptions()
    ) {
        val frames = computeFrameCount(samples, format.channels)
        open(output, format, metadata, options).use { session ->
            session.writeInterleaved(samples, frames)
        }
    }

    /**
     * Encodes one interleaved PCM buffer into a seekable FLAC or Ogg FLAC
     * output channel.
     *
     * The supplied channel remains open after this method returns or throws.
     */
    @JvmOverloads
    fun encode(
        output: SeekableByteChannel,
        format: FlacAudioFormat,
        samples: IntArray,
        metadata: FlacEncodingMetadata = FlacEncodingMetadata(),
        options: FlacEncodingOptions = FlacEncodingOptions()
    ) {
        val frames = computeFrameCount(samples, format.channels)
        open(output, format, metadata, options).use { session ->
            session.writeInterleaved(samples, frames)
        }
    }
}

/**
 * JVM-owned wrapper around one native encoder handle.
 *
 * The session becomes terminal after either a successful finish or a native
 * failure. Further writes are rejected to keep lifecycle bugs obvious.
 */
internal class NativeFlacEncodingSession(
    initialHandle: Long,
    private val format: FlacAudioFormat
) : FlacEncodingSession {
    private var handle: Long = initialHandle
    private var state: SessionState = SessionState.ACTIVE

    override fun writeInterleaved(samples: IntArray, frames: Int) {
        ensureActiveForWrite()
        validateInterleavedPcmChunk(samples, frames, format)
        if (frames == 0) {
            return
        }

        try {
            NativeBindings.writeEncoderInterleaved(handle, samples, frames)
        } catch (t: Throwable) {
            releaseAfterFailure()
            throw t
        }
    }

    override fun finish() {
        when (state) {
            SessionState.FINISHED -> return
            SessionState.FAILED -> return
            SessionState.ACTIVE -> Unit
        }

        val currentHandle = handle
        handle = 0L
        try {
            NativeBindings.finishEncoder(currentHandle)
            state = SessionState.FINISHED
        } catch (t: Throwable) {
            state = SessionState.FAILED
            throw t
        }
    }

    private fun ensureActiveForWrite() {
        if (state != SessionState.ACTIVE || handle == 0L) {
            throw IllegalStateException("The FLAC encoding session is no longer active.")
        }
    }

    /**
     * Best-effort release used after write failures.
     *
     * The native layer still owns the handle after a failed process call, so
     * the JVM proactively releases it to avoid leaking encoder state.
     */
    private fun releaseAfterFailure() {
        if (state != SessionState.ACTIVE) {
            return
        }

        val currentHandle = handle
        handle = 0L
        state = SessionState.FAILED
        if (currentHandle != 0L) {
            NativeBindings.releaseEncoder(currentHandle)
        }
    }

    private enum class SessionState {
        ACTIVE,
        FINISHED,
        FAILED
    }
}

/**
 * Converts the high-level Kotlin models into the Java DTO used at the JNI
 * boundary.
 *
 * Flattening comment entries on the JVM keeps the native layer focused on
 * libFLAC object creation instead of wrapper-specific collection handling.
 */
private fun FlacEncodingMetadata.toNativeRequest(
    format: FlacAudioFormat,
    options: FlacEncodingOptions
): NativeEncodingRequest {
    val commentEntries = comments.toVorbisCommentEntries().toTypedArray()
    val metadataBlockTypes = blocks.map { block -> block.nativeType() }.toIntArray()
    val metadataBlockValues = blocks.map { block -> block.nativeValue() }.toTypedArray()

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
        pictures.toTypedArray(),
        applicationBlocks.toTypedArray(),
        seekTables.toTypedArray(),
        cueSheets.toTypedArray(),
        paddingBlocks.toTypedArray(),
        unknownBlocks.toTypedArray(),
        metadataBlockTypes,
        metadataBlockValues
    )
}

private fun Map<String, List<String>>.toVorbisCommentEntries(): List<String> {
    return entries.flatMap { (key, values) ->
        values.map { value -> "$key=$value" }
    }
}

private fun FlacMetadataBlock.nativeType(): Int {
    return when (this) {
        is FlacMetadataBlock.VorbisComment -> FLAC_METADATA_TYPE_VORBIS_COMMENT
        is FlacMetadataBlock.Picture -> FLAC_METADATA_TYPE_PICTURE
        is FlacMetadataBlock.Application -> FLAC_METADATA_TYPE_APPLICATION
        is FlacMetadataBlock.SeekTable -> FLAC_METADATA_TYPE_SEEKTABLE
        is FlacMetadataBlock.CueSheet -> FLAC_METADATA_TYPE_CUESHEET
        is FlacMetadataBlock.Padding -> FLAC_METADATA_TYPE_PADDING
        is FlacMetadataBlock.Unknown -> unknown.type
    }
}

private fun FlacMetadataBlock.nativeValue(): Any {
    return when (this) {
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
}

/**
 * Validates format values that libFLAC expects to be well-formed.
 *
 * The wrapper deliberately rejects obviously invalid audio descriptions here so
 * callers get stable argument errors before the JNI boundary is crossed.
 */
internal fun validateFlacAudioFormat(format: FlacAudioFormat) {
    require(format.sampleRate > 0) { "Sample rate must be positive." }
    require(format.channels in 1..8) { "Channel count must be between 1 and 8." }
    require(format.bitsPerSample in 4..32) { "Bits per sample must be between 4 and 32." }
    require(format.totalSamplesEstimate == null || format.totalSamplesEstimate >= 0L) {
        "Total sample estimate must be null or non-negative."
    }
}

/**
 * Validates user-facing encoder options before JNI is entered.
 *
 * V1 intentionally exposes a small option subset, so the validation rules stay
 * easy to understand and mirror the wrapper contract closely.
 */
internal fun validateFlacEncodingOptions(options: FlacEncodingOptions) {
    require(options.compressionLevel in 0..8) { "Compression level must be between 0 and 8." }
    require(options.blockSize == null || options.blockSize > 0) { "Block size must be positive when provided." }
    require(options.container == FlacEncodingContainer.OGG || options.oggSerialNumber == null) {
        "Ogg serial number can only be set for Ogg FLAC encoding."
    }
}

/**
 * Validates metadata values that the wrapper can check cheaply on the JVM.
 *
 * The goal is not to reimplement the full FLAC metadata specification, only to
 * catch malformed wrapper input before native allocation work begins.
 */
internal fun validateFlacEncodingMetadata(metadata: FlacEncodingMetadata) {
    if (metadata.blocks.isNotEmpty()) {
        validateOrderedMetadataBlocks(metadata.blocks)
        return
    }

    validateVorbisComments(metadata.comments)
    metadata.pictures.forEach(::validatePictureMetadata)
    metadata.applicationBlocks.forEach(::validateApplicationMetadata)

    require(metadata.seekTables.size <= 1) {
        "At most one SEEKTABLE metadata block can be encoded."
    }
    metadata.seekTables.forEach(::validateSeekTableMetadata)
    metadata.cueSheets.forEach(::validateCueSheetMetadata)
}

private fun validateOrderedMetadataBlocks(blocks: List<FlacMetadataBlock>) {
    require(blocks.count { block -> block is FlacMetadataBlock.VorbisComment } <= 1) {
        "At most one Vorbis comment metadata block can be encoded."
    }
    require(blocks.count { block -> block is FlacMetadataBlock.SeekTable } <= 1) {
        "At most one SEEKTABLE metadata block can be encoded."
    }

    blocks.forEach { block ->
        when (block) {
            is FlacMetadataBlock.VorbisComment -> validateVorbisComments(block.comment.comments, block.comment.vendor)
            is FlacMetadataBlock.Picture -> validatePictureMetadata(block.picture)
            is FlacMetadataBlock.Application -> validateApplicationMetadata(block.application)
            is FlacMetadataBlock.SeekTable -> validateSeekTableMetadata(block.seekTable)
            is FlacMetadataBlock.CueSheet -> validateCueSheetMetadata(block.cueSheet)
            is FlacMetadataBlock.Padding,
            is FlacMetadataBlock.Unknown -> Unit
        }
    }
}

private fun validateVorbisComments(comments: Map<String, List<String>>, vendor: String? = null) {
    vendor?.let { nonNullVendor ->
        require('\u0000' !in nonNullVendor) { "Vorbis vendor must not contain embedded NUL characters." }
    }

    comments.forEach { (key, values) ->
        require(key.isNotBlank()) { "Vorbis comment keys must not be blank." }
        require('=' !in key) { "Vorbis comment keys must not contain '='." }
        require('\u0000' !in key) { "Vorbis comment keys must not contain embedded NUL characters." }
        values.forEach { value ->
            require('\u0000' !in value) { "Vorbis comment values must not contain embedded NUL characters." }
        }
    }
    validateVorbisCommentBlockLength(vendor, comments)
}

private fun validateVorbisCommentBlockLength(vendor: String?, comments: Map<String, List<String>>) {
    /*
     * Vorbis-comment block layout:
     * 4 bytes vendor length + vendor bytes + 4 bytes entry count, then each
     * entry as 4 bytes length + UTF-8 "KEY=value" bytes.
     */
    var totalBytes = 8L + (vendor?.utf8ByteCount()?.toLong() ?: 0L)
    comments.forEach { (key, values) ->
        values.forEach { value ->
            totalBytes += 4L + "$key=$value".utf8ByteCount().toLong()
        }
    }
    require(totalBytes <= FLAC_METADATA_MAX_BLOCK_LENGTH) {
        "VORBIS_COMMENT metadata length must fit the FLAC 24-bit metadata length field."
    }
}

private fun validatePictureMetadata(picture: FlacPicture) {
    require(picture.type >= 0) { "Picture type must be non-negative." }
    require(picture.mimeType.isNotBlank()) { "Picture MIME type must not be blank." }
    require('\u0000' !in picture.mimeType) { "Picture MIME type must not contain embedded NUL characters." }
    require('\u0000' !in picture.description) { "Picture description must not contain embedded NUL characters." }
    require(picture.width >= 0) { "Picture width must be non-negative." }
    require(picture.height >= 0) { "Picture height must be non-negative." }
    require(picture.depth >= 0) { "Picture depth must be non-negative." }
    require(picture.colors >= 0) { "Picture colour count must be non-negative." }

    val blockBytes = FLAC_PICTURE_FIXED_FIELD_BYTES.toLong() +
            picture.mimeType.utf8ByteCount().toLong() +
            picture.description.utf8ByteCount().toLong() +
            picture.data.size.toLong()
    require(blockBytes <= FLAC_METADATA_MAX_BLOCK_LENGTH) {
        "PICTURE metadata length must fit the FLAC 24-bit metadata length field."
    }
}

private fun validateApplicationMetadata(application: FlacApplicationBlock) {
    val blockBytes = 4L + application.data.size.toLong()
    require(blockBytes <= FLAC_METADATA_MAX_BLOCK_LENGTH) {
        "APPLICATION metadata length must fit the FLAC 24-bit metadata length field."
    }
}

private fun validateSeekTableMetadata(seekTable: FlacSeekTable) {
    seekTable.points.forEach { point ->
        require(point.sampleNumber >= FLAC_SEEKPOINT_PLACEHOLDER_SAMPLE_NUMBER) {
            "Seek point sample number must be non-negative or -1 for a placeholder."
        }
        require(point.streamOffset >= 0L) { "Seek point stream offset must be non-negative." }
        require(point.frameSamples in 0..FLAC_SEEKPOINT_MAX_FRAME_SAMPLES) {
            "Seek point frame sample count must fit the FLAC 16-bit field."
        }
    }
}

private fun validateCueSheetMetadata(cueSheet: FlacCueSheet) {
    require(cueSheet.mediaCatalogNumber.isFixedAsciiField(FLAC_CUESHEET_MAX_FIXED_FIELD_BYTES)) {
        "CUESHEET media catalog number must be printable ASCII and at most 128 bytes."
    }
    require(cueSheet.leadIn >= 0L) { "CUESHEET lead-in must be non-negative." }
    require(cueSheet.tracks.size <= FLAC_CUESHEET_MAX_LIST_ITEMS) {
        "CUESHEET track count must fit the FLAC 8-bit field."
    }
    cueSheet.tracks.forEach { track ->
        require(track.offset >= 0L) { "CUESHEET track offset must be non-negative." }
        require(track.number in 0..255) { "CUESHEET track number must fit the FLAC 8-bit field." }
        require(track.isrc.isFixedAsciiField(FLAC_CUESHEET_MAX_ISRC_BYTES)) {
            "CUESHEET track ISRC must be printable ASCII and at most 12 bytes."
        }
        require(track.type in 0..1) { "CUESHEET track type must be 0 or 1." }
        require(track.indices.size <= FLAC_CUESHEET_MAX_LIST_ITEMS) {
            "CUESHEET index count must fit the FLAC 8-bit field."
        }
        track.indices.forEach { index ->
            require(index.offset >= 0L) { "CUESHEET index offset must be non-negative." }
            require(index.number in 0..255) { "CUESHEET index number must fit the FLAC 8-bit field." }
        }
    }
}

private fun String.isFixedAsciiField(maxBytes: Int): Boolean {
    if (length > maxBytes) {
        return false
    }
    return all { char -> char.code in 0x20..0x7e }
}

private fun String.utf8ByteCount(): Int {
    return toByteArray(Charsets.UTF_8).size
}

/**
 * Performs light path validation for encoder outputs.
 *
 * Missing parents are not rejected here because they are a useful integration
 * test for the native file-open failure path. The JVM only rejects path shapes
 * that are clearly inconsistent before libFLAC is involved.
 */
internal fun validateNativeFlacOutputPath(path: Path): Path {
    val normalized = path.toAbsolutePath().normalize()
    val parent = normalized.parent
    require(parent == null || !Files.exists(parent) || Files.isDirectory(parent)) {
        "Output parent path is not a directory."
    }
    return normalized
}

/**
 * Converts the flat sample array into a frame count for one-shot encode calls.
 *
 * One-shot encoding always uses the full supplied array, so the only valid
 * layout is a sample count divisible by the channel count.
 */
internal fun computeFrameCount(samples: IntArray, channels: Int): Int {
    require(channels > 0) { "Channel count must be positive." }
    require(samples.size % channels == 0) {
        "Sample array length must be divisible by the channel count."
    }
    return samples.size / channels
}

/**
 * Validates a PCM chunk against the declared FLAC audio format.
 *
 * Range checks stay on the JVM side so out-of-range PCM input is reported as a
 * caller contract violation instead of a native encoder failure.
 */
internal fun validateInterleavedPcmChunk(samples: IntArray, frames: Int, format: FlacAudioFormat) {
    require(frames >= 0) { "Frame count must be non-negative." }

    val expectedSampleCount = frames.toLong() * format.channels.toLong()
    require(expectedSampleCount <= Int.MAX_VALUE) { "Frame count is too large for one chunk." }
    require(samples.size == expectedSampleCount.toInt()) {
        "Sample array length must equal frames * channels."
    }

    val minSample: Int
    val maxSample: Int
    if (format.bitsPerSample == 32) {
        minSample = Int.MIN_VALUE
        maxSample = Int.MAX_VALUE
    } else {
        val limit = 1L shl (format.bitsPerSample - 1)
        minSample = (-limit).toInt()
        maxSample = (limit - 1L).toInt()
    }

    for (sample in samples) {
        require(sample in minSample..maxSample) {
            "PCM sample $sample is outside the signed ${format.bitsPerSample}-bit range [$minSample, $maxSample]."
        }
    }
}
