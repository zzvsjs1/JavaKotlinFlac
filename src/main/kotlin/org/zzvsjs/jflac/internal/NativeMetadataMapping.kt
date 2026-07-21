package org.zzvsjs.jflac.internal

import org.zzvsjs.jflac.FlacMetadata
import org.zzvsjs.jflac.FlacMetadataBlock
import org.zzvsjs.jflac.FlacVorbisComment

private const val FLAC_METADATA_TYPE_PADDING = 1
private const val FLAC_METADATA_TYPE_APPLICATION = 2
private const val FLAC_METADATA_TYPE_SEEKTABLE = 3
private const val FLAC_METADATA_TYPE_VORBIS_COMMENT = 4
private const val FLAC_METADATA_TYPE_CUESHEET = 5
private const val FLAC_METADATA_TYPE_PICTURE = 6

/**
 * Converts the JNI metadata DTO into the public immutable wrapper model.
 */
internal fun NativeMetadataPayload.toPublicMetadata(): FlacMetadata {
    val groupedComments = linkedMapOf<String, MutableList<String>>()
    for (entry in commentEntries) {
        val separator = entry.indexOf('=')
        if (separator <= 0) {
            continue
        }

        val key = entry.substring(0, separator)
        val value = entry.substring(separator + 1)
        groupedComments.getOrPut(key) { mutableListOf() }.add(value)
    }

    val vorbisComment = vendor?.let { nonNullVendor ->
        FlacVorbisComment(
            vendor = nonNullVendor,
            comments = groupedComments.mapValues { (_, values) -> values.toList() }
        )
    }

    val blocks = metadataBlockTypes.zip(metadataBlockIndices).mapNotNull { (type, index) ->
        when (type) {
            FLAC_METADATA_TYPE_PADDING -> paddingBlocks.getOrNull(index)?.let(FlacMetadataBlock::Padding)
            FLAC_METADATA_TYPE_APPLICATION -> applicationBlocks.getOrNull(index)?.let(FlacMetadataBlock::Application)
            FLAC_METADATA_TYPE_SEEKTABLE -> seekTables.getOrNull(index)?.let(FlacMetadataBlock::SeekTable)
            FLAC_METADATA_TYPE_VORBIS_COMMENT -> vorbisComment?.let(FlacMetadataBlock::VorbisComment)
            FLAC_METADATA_TYPE_CUESHEET -> cueSheets.getOrNull(index)?.let(FlacMetadataBlock::CueSheet)
            FLAC_METADATA_TYPE_PICTURE -> pictures.getOrNull(index)?.let(FlacMetadataBlock::Picture)
            else -> unknownBlocks.getOrNull(index)?.let(FlacMetadataBlock::Unknown)
        }
    }

    return FlacMetadata(
        streamInfo = streamInfo,
        vorbisComment = vorbisComment,
        pictures = pictures.toList(),
        applicationBlocks = applicationBlocks.toList(),
        seekTables = seekTables.toList(),
        cueSheets = cueSheets.toList(),
        paddingBlocks = paddingBlocks.toList(),
        unknownBlocks = unknownBlocks.toList(),
        blocks = blocks
    )
}
