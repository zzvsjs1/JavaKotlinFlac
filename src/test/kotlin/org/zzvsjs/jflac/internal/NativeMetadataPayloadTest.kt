package org.zzvsjs.jflac.internal

import org.zzvsjs.jflac.FlacApplicationBlock
import org.zzvsjs.jflac.FlacCueSheet
import org.zzvsjs.jflac.FlacCueSheetIndex
import org.zzvsjs.jflac.FlacCueSheetTrack
import org.zzvsjs.jflac.FlacMetadataBlock
import org.zzvsjs.jflac.FlacPicture
import org.zzvsjs.jflac.FlacPaddingBlock
import org.zzvsjs.jflac.FlacSeekPoint
import org.zzvsjs.jflac.FlacSeekTable
import org.zzvsjs.jflac.FlacStreamInfo
import org.zzvsjs.jflac.FlacUnknownMetadataBlock
import org.zzvsjs.jflac.toEncodingMetadata
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativeMetadataPayloadTest {
    @Test
    fun nativePayloadGroupsVorbisCommentsByKey() {
        val md5 = ByteArray(16) { index -> index.toByte() }
        val streamInfo = streamInfo(md5)
        val picture = FlacPicture(
            type = 3,
            mimeType = "image/png",
            description = "Cover",
            width = 16,
            height = 16,
            depth = 24,
            colors = 0,
            data = byteArrayOf(1, 2, 3)
        )
        val application = FlacApplicationBlock(
            id = byteArrayOf(0x4a, 0x46, 0x4c, 0x41),
            data = byteArrayOf(9, 8, 7)
        )
        val seekTable = FlacSeekTable(
            points = listOf(
                FlacSeekPoint(sampleNumber = 0, streamOffset = 0, frameSamples = 4096),
                FlacSeekPoint(sampleNumber = 4096, streamOffset = 2048, frameSamples = 4096)
            )
        )
        val cueSheet = FlacCueSheet(
            mediaCatalogNumber = "1234567890123",
            leadIn = 88_200,
            isCd = true,
            tracks = listOf(
                FlacCueSheetTrack(
                    offset = 0,
                    number = 1,
                    isrc = "AUZZ12600001",
                    type = 0,
                    preEmphasis = false,
                    indices = listOf(
                        FlacCueSheetIndex(offset = 0, number = 1)
                    )
                )
            )
        )
        val padding = FlacPaddingBlock(length = 31)
        val unknown = FlacUnknownMetadataBlock(type = 42, data = byteArrayOf(5, 6, 7, 8))
        val payload = NativeMetadataPayload(
            streamInfo,
            "encoder",
            arrayOf("ARTIST=One", "ARTIST=Two", "TITLE=Track"),
            arrayOf(picture),
            arrayOf(application),
            arrayOf(seekTable),
            arrayOf(cueSheet),
            arrayOf(padding),
            arrayOf(unknown),
            intArrayOf(1, 2, 7, 4, 6, 3, 5),
            intArrayOf(0, 0, 0, 0, 0, 0, 0)
        )

        val metadata = payload.toPublicMetadata()

        assertEquals(streamInfo, metadata.streamInfo)
        assertContentEquals(md5, metadata.streamInfo.md5Signature)
        assertEquals("encoder", metadata.vorbisComment?.vendor)
        assertEquals(
            mapOf(
                "ARTIST" to listOf("One", "Two"),
                "TITLE" to listOf("Track")
            ),
            metadata.vorbisComment?.comments
        )
        assertEquals(listOf(picture), metadata.pictures)
        assertEquals(1, metadata.applicationBlocks.size)
        assertContentEquals(application.id, metadata.applicationBlocks.single().id)
        assertContentEquals(application.data, metadata.applicationBlocks.single().data)
        assertEquals(listOf(seekTable), metadata.seekTables)
        assertEquals(listOf(cueSheet), metadata.cueSheets)
        assertEquals(listOf(padding), metadata.paddingBlocks)
        assertEquals(1, metadata.unknownBlocks.size)
        assertEquals(unknown.type, metadata.unknownBlocks.single().type)
        assertContentEquals(unknown.data, metadata.unknownBlocks.single().data)
        assertEquals(
            listOf(
                FlacMetadataBlock.Padding(padding),
                FlacMetadataBlock.Application(application),
                FlacMetadataBlock.Unknown(unknown),
                FlacMetadataBlock.VorbisComment(metadata.vorbisComment!!),
                FlacMetadataBlock.Picture(picture),
                FlacMetadataBlock.SeekTable(seekTable),
                FlacMetadataBlock.CueSheet(cueSheet)
            ),
            metadata.blocks
        )

        val encodingMetadata = metadata.toEncodingMetadata()
        assertEquals(metadata.blocks, encodingMetadata.blocks)
        assertEquals(listOf(padding), encodingMetadata.paddingBlocks)
        assertEquals(1, encodingMetadata.unknownBlocks.size)
        assertEquals(unknown.type, encodingMetadata.unknownBlocks.single().type)
        assertContentEquals(unknown.data, encodingMetadata.unknownBlocks.single().data)
    }

    @Test
    fun nativePayloadWithoutVendorHasNoVorbisComment() {
        val payload = NativeMetadataPayload(
            streamInfo(),
            null,
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            intArrayOf(),
            intArrayOf()
        )

        val metadata = payload.toPublicMetadata()

        assertNull(metadata.vorbisComment)
        assertEquals(emptyList(), metadata.pictures)
        assertEquals(emptyList(), metadata.applicationBlocks)
        assertEquals(emptyList(), metadata.seekTables)
        assertEquals(emptyList(), metadata.cueSheets)
        assertEquals(emptyList(), metadata.paddingBlocks)
        assertEquals(emptyList(), metadata.unknownBlocks)
        assertEquals(emptyList(), metadata.blocks)
    }

    private fun streamInfo(md5Signature: ByteArray = ByteArray(16)): FlacStreamInfo {
        return FlacStreamInfo(
            sampleRate = 44100,
            channels = 2,
            bitsPerSample = 16,
            totalSamples = 10,
            minBlockSize = 16,
            maxBlockSize = 4096,
            minFrameSize = 0,
            maxFrameSize = 0,
            md5Signature = md5Signature
        )
    }
}
