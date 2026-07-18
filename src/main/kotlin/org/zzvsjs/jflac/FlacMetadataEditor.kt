package org.zzvsjs.jflac

import org.zzvsjs.jflac.internal.NativeAccess
import java.nio.file.Path
import kotlin.io.path.absolutePathString

/**
 * Options used when committing metadata edits to an existing FLAC file.
 *
 * [usePadding] lets libFLAC replace removed blocks with padding where that is
 * cheaper than moving all following audio data. [preserveFileStats] asks
 * libFLAC to preserve file metadata such as timestamps where the platform and
 * filesystem allow it; it is not a guarantee across every filesystem.
 *
 * Metadata editing is file based and currently targets native FLAC metadata
 * chains. Ogg FLAC files can be read and encoded, but in-place Ogg metadata
 * editing is not exposed by this editor.
 */
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

    /**
     * Replaces the whole editable non-STREAMINFO block list.
     *
     * Block order is preserved exactly. Validation is deferred until the editor
     * commits the session, so this method is useful for round-trip workflows
     * that copy [FlacMetadata.blocks], adjust a few entries, then write the
     * whole ordered list back.
     */
    fun replaceBlocks(blocks: List<FlacMetadataBlock>): FlacMetadataEditSession {
        mutableBlocks.clear()
        mutableBlocks.addAll(blocks)
        return this
    }

    /**
     * Adds or replaces the Vorbis comment block while preserving its vendor
     * string when one already exists.
     *
     * Passing an empty map removes the comment block. Keys and values are
     * validated during commit: keys must not be blank, keys cannot contain `=`,
     * and neither keys nor values may contain embedded NUL characters.
     */
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

    /** Removes every Vorbis comment block from the editable ordered block list. */
    fun removeVorbisComments(): FlacMetadataEditSession {
        mutableBlocks.removeAll { block -> block is FlacMetadataBlock.VorbisComment }
        return this
    }

    /** Removes every picture block while leaving comments and other metadata intact. */
    fun removePictures(): FlacMetadataEditSession {
        mutableBlocks.removeAll { block -> block is FlacMetadataBlock.Picture }
        return this
    }

    /**
     * Appends one picture block to the end of the editable metadata block list.
     *
     * libFLAC checks picture legality during commit. Use
     * [FlacFormat.pictureViolation] first when accepting user-supplied artwork
     * and you want to report a precise validation message before editing.
     */
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
 *
 * Example:
 *
 * ```
 * FlacMetadataEditor().edit(Path.of("song.flac")) { session ->
 *     session.setVorbisComments(mapOf("TITLE" to listOf("New title")))
 * }
 * ```
 *
 * The write is not presented as an atomic transaction by this wrapper. If the
 * native metadata-chain write fails, callers should treat the target file as a
 * normal file-system write failure and decide whether to restore their own
 * backup.
 */
class FlacMetadataEditor {
    /**
     * Reads existing metadata, lets [action] mutate an in-memory session, then
     * commits the resulting block list with default [FlacMetadataEditOptions].
     */
    fun edit(path: Path, action: FlacMetadataEditAction) {
        edit(path, FlacMetadataEditOptions(), action)
    }

    /**
     * Reads existing metadata, lets [action] mutate an in-memory session, then
     * commits the resulting block list with [options].
     *
     * @throws IllegalArgumentException for invalid metadata shapes, unsupported
     *   paths, or malformed values detected before JNI.
     * @throws FlacMetadataEditException for libFLAC metadata-chain failures.
     * @throws NativeLoadException when the native runtime cannot be loaded.
     */
    fun edit(path: Path, options: FlacMetadataEditOptions, action: FlacMetadataEditAction) {
        val session = FlacMetadataEditSession(FlacMetadataReader().read(path))
        action.edit(session)
        replace(path, session.toEncodingMetadata(), options)
    }

    /** Replaces editable metadata blocks using default [FlacMetadataEditOptions]. */
    fun replace(path: Path, metadata: FlacEncodingMetadata) {
        replace(path, metadata, FlacMetadataEditOptions())
    }

    /**
     * Replaces editable metadata blocks on [path].
     *
     * When [FlacEncodingMetadata.blocks] is not empty, it is the authoritative ordered block
     * list and grouped convenience fields such as [FlacEncodingMetadata.comments]
     * are ignored. STREAMINFO is not writable through this API because it
     * describes the existing audio frames.
     */
    fun replace(path: Path, metadata: FlacEncodingMetadata, options: FlacMetadataEditOptions) {
        val normalizedPath = validateNativeFlacMetadataEditPath(path)
        validateFlacEncodingMetadata(metadata)

        FlacNativeLoader.load()
        NativeAccess.writeMetadata(
            normalizedPath.absolutePathString(),
            metadata,
            options.usePadding,
            options.preserveFileStats
        )
    }
}
