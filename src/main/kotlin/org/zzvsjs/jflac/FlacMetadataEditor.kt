package org.zzvsjs.jflac

import org.zzvsjs.jflac.internal.NativeBindings
import org.zzvsjs.jflac.internal.NativeMetadataEditRequest
import java.nio.file.Path
import kotlin.io.path.absolutePathString

private const val EDIT_METADATA_TYPE_PADDING = 1
private const val EDIT_METADATA_TYPE_APPLICATION = 2
private const val EDIT_METADATA_TYPE_SEEKTABLE = 3
private const val EDIT_METADATA_TYPE_VORBIS_COMMENT = 4
private const val EDIT_METADATA_TYPE_CUESHEET = 5
private const val EDIT_METADATA_TYPE_PICTURE = 6

/** Options used when committing metadata edits to an existing FLAC file. */
data class FlacMetadataEditOptions @JvmOverloads constructor(
    val usePadding: Boolean = true,
    val preserveFileStats: Boolean = false
)

/** Java-friendly callback for [FlacMetadataEditor.edit]. */
fun interface FlacMetadataEditAction {
    fun edit(session: FlacMetadataEditSession)
}

/**
 * Mutable JVM-side metadata edit buffer.
 *
 * Edits are collected in memory and committed once by [FlacMetadataEditor].
 * STREAMINFO is deliberately not exposed for mutation because libFLAC owns the
 * audio stream description in existing files.
 */
class FlacMetadataEditSession internal constructor(
    val originalMetadata: FlacMetadata
) {
    private val mutableBlocks = originalMetadata.blocks.toMutableList()

    val blocks: List<FlacMetadataBlock>
        get() = mutableBlocks.toList()

    fun replaceBlocks(blocks: List<FlacMetadataBlock>): FlacMetadataEditSession {
        mutableBlocks.clear()
        mutableBlocks.addAll(blocks)
        return this
    }

    fun setVorbisComments(comments: Map<String, List<String>>): FlacMetadataEditSession {
        if (comments.isEmpty()) {
            return removeVorbisComments()
        }

        val existingIndex = mutableBlocks.indexOfFirst { block -> block is FlacMetadataBlock.VorbisComment }
        val vendor = when {
            existingIndex >= 0 -> (mutableBlocks[existingIndex] as FlacMetadataBlock.VorbisComment).comment.vendor
            originalMetadata.vorbisComment != null -> originalMetadata.vorbisComment.vendor
            else -> ""
        }
        val block = FlacMetadataBlock.VorbisComment(
            FlacVorbisComment(
                vendor = vendor,
                comments = comments
            )
        )

        if (existingIndex >= 0) {
            mutableBlocks[existingIndex] = block
        } else {
            mutableBlocks.add(0, block)
        }
        return this
    }

    fun removeVorbisComments(): FlacMetadataEditSession {
        mutableBlocks.removeAll { block -> block is FlacMetadataBlock.VorbisComment }
        return this
    }

    fun removePictures(): FlacMetadataEditSession {
        mutableBlocks.removeAll { block -> block is FlacMetadataBlock.Picture }
        return this
    }

    fun addPicture(picture: FlacPicture): FlacMetadataEditSession {
        mutableBlocks.add(FlacMetadataBlock.Picture(picture))
        return this
    }

    internal fun toEncodingMetadata(): FlacEncodingMetadata {
        return FlacEncodingMetadata(blocks = mutableBlocks.toList())
    }
}

/**
 * Existing-file FLAC metadata editor backed by libFLAC metadata-chain writes.
 *
 * This edits metadata blocks only. Audio frames are preserved by libFLAC and
 * are not decoded or re-encoded by the wrapper.
 */
class FlacMetadataEditor {
    fun edit(path: Path, action: FlacMetadataEditAction) {
        edit(path, FlacMetadataEditOptions(), action)
    }

    fun edit(path: Path, options: FlacMetadataEditOptions, action: FlacMetadataEditAction) {
        val session = FlacMetadataEditSession(FlacMetadataReader().read(path))
        action.edit(session)
        replace(path, session.toEncodingMetadata(), options)
    }

    fun replace(path: Path, metadata: FlacEncodingMetadata) {
        replace(path, metadata, FlacMetadataEditOptions())
    }

    fun replace(path: Path, metadata: FlacEncodingMetadata, options: FlacMetadataEditOptions) {
        val normalizedPath = validateNativeFlacMetadataEditPath(path)
        validateFlacEncodingMetadata(metadata)

        FlacNativeLoader.load()
        NativeBindings.writeMetadata(
            normalizedPath.absolutePathString(),
            metadata.toNativeEditRequest(),
            options.usePadding,
            options.preserveFileStats
        )
    }
}

private fun FlacEncodingMetadata.toNativeEditRequest(): NativeMetadataEditRequest {
    val commentEntries = comments.toEditVorbisCommentEntries().toTypedArray()
    val metadataBlockTypes = blocks.map { block -> block.editNativeType() }.toIntArray()
    val metadataBlockValues = blocks.map { block -> block.editNativeValue() }.toTypedArray()

    return NativeMetadataEditRequest(
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

private fun Map<String, List<String>>.toEditVorbisCommentEntries(): List<String> {
    return entries.flatMap { (key, values) ->
        values.map { value -> "$key=$value" }
    }
}

private fun FlacMetadataBlock.editNativeType(): Int {
    return when (this) {
        is FlacMetadataBlock.VorbisComment -> EDIT_METADATA_TYPE_VORBIS_COMMENT
        is FlacMetadataBlock.Picture -> EDIT_METADATA_TYPE_PICTURE
        is FlacMetadataBlock.Application -> EDIT_METADATA_TYPE_APPLICATION
        is FlacMetadataBlock.SeekTable -> EDIT_METADATA_TYPE_SEEKTABLE
        is FlacMetadataBlock.CueSheet -> EDIT_METADATA_TYPE_CUESHEET
        is FlacMetadataBlock.Padding -> EDIT_METADATA_TYPE_PADDING
        is FlacMetadataBlock.Unknown -> unknown.type
    }
}

private fun FlacMetadataBlock.editNativeValue(): Any {
    return when (this) {
        is FlacMetadataBlock.VorbisComment -> comment.comments.toEditVorbisCommentEntries().toTypedArray()
        is FlacMetadataBlock.Picture -> picture
        is FlacMetadataBlock.Application -> application
        is FlacMetadataBlock.SeekTable -> seekTable
        is FlacMetadataBlock.CueSheet -> cueSheet
        is FlacMetadataBlock.Padding -> padding
        is FlacMetadataBlock.Unknown -> unknown
    }
}
