package org.zzvsjs.jflac

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlacEncoderIntegrationTest {
    @Test
    fun oneShotEncodeRoundTripsInterleavedPcm() {
        val frames = 17
        val format = FlacAudioFormat(
            sampleRate = 44_100,
            channels = 2,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val samples = deterministicPcm(frames, format)
        val output = Files.createTempFile("jflac-encode-roundtrip", ".flac")

        try {
            FlacEncoder().encode(output, format, samples)

            assertTrue(Files.size(output) > 0)
            val decoded = FlacDecoder().decode(output)

            assertEquals(format.sampleRate, decoded.streamInfo.sampleRate)
            assertEquals(format.channels, decoded.streamInfo.channels)
            assertEquals(format.bitsPerSample, decoded.streamInfo.bitsPerSample)
            assertEquals(frames.toLong(), decoded.totalFrames)
            assertContentEquals(samples, decoded.interleavedSamples)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun oneShotOggEncodeRoundTripsInterleavedPcm() {
        val frames = 21
        val format = FlacAudioFormat(
            sampleRate = 44_100,
            channels = 2,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val samples = deterministicPcm(frames, format)
        val metadata = FlacEncodingMetadata(
            comments = mapOf("TITLE" to listOf("Ogg encode"))
        )
        val output = Files.createTempFile("jflac-ogg-encode-roundtrip", ".oga")

        try {
            FlacEncoder().encode(
                output = output,
                format = format,
                samples = samples,
                metadata = metadata,
                options = FlacEncodingOptions(
                    container = FlacEncodingContainer.OGG,
                    oggSerialNumber = 37
                )
            )

            val bytes = Files.readAllBytes(output)
            assertContentEquals(
                byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte()),
                bytes.copyOf(4)
            )

            val decoded = FlacDecoder().decode(output)
            val decodedMetadata = FlacMetadataReader().read(output)

            assertEquals(format.sampleRate, decoded.streamInfo.sampleRate)
            assertEquals(format.channels, decoded.streamInfo.channels)
            assertEquals(format.bitsPerSample, decoded.streamInfo.bitsPerSample)
            assertEquals(frames.toLong(), decoded.totalFrames)
            assertContentEquals(samples, decoded.interleavedSamples)
            assertEquals(listOf("Ogg encode"), decodedMetadata.vorbisComment?.comments?.get("TITLE"))
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun streamingEncodeRoundTripsMultipleChunks() {
        val firstChunkFrames = 11
        val secondChunkFrames = 7
        val format = FlacAudioFormat(
            sampleRate = 48_000,
            channels = 2,
            bitsPerSample = 16,
            totalSamplesEstimate = (firstChunkFrames + secondChunkFrames).toLong()
        )
        val firstChunk = deterministicPcm(firstChunkFrames, format)
        val secondChunk = deterministicPcm(secondChunkFrames, format, frameOffset = 1_000)
        val expectedSamples = firstChunk + secondChunk
        val output = Files.createTempFile("jflac-streaming-encode", ".flac")

        try {
            FlacEncoder().open(output, format).use { session ->
                session.writeInterleaved(firstChunk, firstChunkFrames)
                session.writeInterleaved(IntArray(0), 0)
                session.writeInterleaved(secondChunk, secondChunkFrames)
                session.finish()
                session.finish()
            }

            val decoded = FlacDecoder().decode(output)

            assertEquals((firstChunkFrames + secondChunkFrames).toLong(), decoded.totalFrames)
            assertContentEquals(expectedSamples, decoded.interleavedSamples)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun encodedVorbisCommentsRoundTripThroughMetadataReader() {
        val frames = 13
        val format = FlacAudioFormat(
            sampleRate = 32_000,
            channels = 1,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val metadata = FlacEncodingMetadata(
            comments = mapOf(
                "TITLE" to listOf("Round trip"),
                "ARTIST" to listOf("First", "Second")
            )
        )
        val output = Files.createTempFile("jflac-comment-encode", ".flac")

        try {
            FlacEncoder().encode(
                output = output,
                format = format,
                samples = deterministicPcm(frames, format),
                metadata = metadata
            )

            val decodedMetadata = FlacMetadataReader().read(output)

            assertEquals(listOf("Round trip"), decodedMetadata.vorbisComment?.comments?.get("TITLE"))
            assertEquals(listOf("First", "Second"), decodedMetadata.vorbisComment?.comments?.get("ARTIST"))
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun encodedPictureRoundTripsThroughMetadataReader() {
        val frames = 5
        val format = FlacAudioFormat(
            sampleRate = 22_050,
            channels = 1,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val pictureData = byteArrayOf(0x01, 0x23, 0x45, 0x67)
        val picture = FlacPicture(
            type = 3,
            mimeType = "image/png",
            description = "Cover",
            width = 1,
            height = 1,
            depth = 24,
            colors = 0,
            data = pictureData
        )
        val output = Files.createTempFile("jflac-picture-encode", ".flac")

        try {
            FlacEncoder().encode(
                output = output,
                format = format,
                samples = deterministicPcm(frames, format),
                metadata = FlacEncodingMetadata(pictures = listOf(picture))
            )

            val decodedPicture = assertNotNull(FlacMetadataReader().read(output).pictures.singleOrNull())

            assertEquals(picture.type, decodedPicture.type)
            assertEquals(picture.mimeType, decodedPicture.mimeType)
            assertEquals(picture.description, decodedPicture.description)
            assertEquals(picture.width, decodedPicture.width)
            assertEquals(picture.height, decodedPicture.height)
            assertEquals(picture.depth, decodedPicture.depth)
            assertEquals(picture.colors, decodedPicture.colors)
            assertContentEquals(pictureData, decodedPicture.data)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun encodedApplicationBlocksRoundTripThroughMetadataReader() {
        val frames = 9
        val format = FlacAudioFormat(
            sampleRate = 44_100,
            channels = 1,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val application = FlacApplicationBlock(
            id = byteArrayOf('J'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), 'C'.code.toByte()),
            data = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        )
        val output = Files.createTempFile("jflac-application-encode", ".flac")

        try {
            FlacEncoder().encode(
                output = output,
                format = format,
                samples = deterministicPcm(frames, format),
                metadata = FlacEncodingMetadata(applicationBlocks = listOf(application))
            )

            val decodedApplication = FlacMetadataReader().read(output).applicationBlocks.single()

            assertContentEquals(application.id, decodedApplication.id)
            assertContentEquals(application.data, decodedApplication.data)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun encodedSeekTableRoundTripsThroughMetadataReader() {
        val frames = 32
        val format = FlacAudioFormat(
            sampleRate = 48_000,
            channels = 2,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val seekTable = FlacSeekTable(
            points = listOf(
                FlacSeekPoint(sampleNumber = 0, streamOffset = 0, frameSamples = frames)
            )
        )
        val output = Files.createTempFile("jflac-seektable-encode", ".flac")

        try {
            FlacEncoder().encode(
                output = output,
                format = format,
                samples = deterministicPcm(frames, format),
                metadata = FlacEncodingMetadata(seekTables = listOf(seekTable))
            )

            val decodedSeekTable = FlacMetadataReader().read(output).seekTables.single()

            assertEquals(seekTable, decodedSeekTable)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun encodedCueSheetRoundTripsThroughMetadataReader() {
        val frames = 44_100
        val format = FlacAudioFormat(
            sampleRate = 44_100,
            channels = 2,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val cueSheet = FlacCueSheet(
            mediaCatalogNumber = "1234567890123",
            leadIn = 0,
            isCd = false,
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
                ),
                FlacCueSheetTrack(
                    offset = frames.toLong(),
                    number = 170,
                    isrc = "",
                    type = 0,
                    preEmphasis = false,
                    indices = emptyList()
                )
            )
        )
        val output = Files.createTempFile("jflac-cuesheet-encode", ".flac")

        try {
            FlacEncoder().encode(
                output = output,
                format = format,
                samples = deterministicPcm(frames, format),
                metadata = FlacEncodingMetadata(cueSheets = listOf(cueSheet))
            )

            val decodedCueSheet = FlacMetadataReader().read(output).cueSheets.single()

            assertEquals(cueSheet, decodedCueSheet)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun encodedPaddingAndUnknownBlocksRoundTripThroughMetadataReader() {
        val frames = 8
        val format = FlacAudioFormat(
            sampleRate = 44_100,
            channels = 1,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val unknownData = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val output = Files.createTempFile("jflac-raw-metadata-encode", ".flac")

        try {
            FlacEncoder().encode(
                output = output,
                format = format,
                samples = deterministicPcm(frames, format),
                metadata = FlacEncodingMetadata(
                    paddingBlocks = listOf(FlacPaddingBlock(length = 19)),
                    unknownBlocks = listOf(
                        FlacUnknownMetadataBlock(type = 42, data = unknownData)
                    )
                )
            )

            val metadata = FlacMetadataReader().read(output)

            assertEquals(19, metadata.paddingBlocks.single().length)
            assertEquals(42, metadata.unknownBlocks.single().type)
            assertContentEquals(unknownData, metadata.unknownBlocks.single().data)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun encodedOrderedMetadataBlocksRoundTripInCallerOrder() {
        val frames = 8
        val format = FlacAudioFormat(
            sampleRate = 44_100,
            channels = 1,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val padding = FlacPaddingBlock(length = 7)
        val application = FlacApplicationBlock(
            id = byteArrayOf('O'.code.toByte(), 'R'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte()),
            data = byteArrayOf(0x01, 0x02)
        )
        val unknown = FlacUnknownMetadataBlock(type = 42, data = byteArrayOf(0x11, 0x22))
        val comment = FlacVorbisComment(
            vendor = "ordered-vendor",
            comments = mapOf("TITLE" to listOf("Ordered"))
        )
        val output = Files.createTempFile("jflac-ordered-metadata-encode", ".flac")

        try {
            FlacEncoder().encode(
                output = output,
                format = format,
                samples = deterministicPcm(frames, format),
                metadata = FlacEncodingMetadata(
                    blocks = listOf(
                        FlacMetadataBlock.Padding(padding),
                        FlacMetadataBlock.Application(application),
                        FlacMetadataBlock.Unknown(unknown),
                        FlacMetadataBlock.VorbisComment(comment)
                    )
                )
            )

            val blocks = FlacMetadataReader().read(output).blocks

            assertIs<FlacMetadataBlock.Padding>(blocks[0])
            assertEquals(padding, (blocks[0] as FlacMetadataBlock.Padding).padding)
            assertIs<FlacMetadataBlock.Application>(blocks[1])
            assertContentEquals(application.id, (blocks[1] as FlacMetadataBlock.Application).application.id)
            assertContentEquals(application.data, (blocks[1] as FlacMetadataBlock.Application).application.data)
            assertIs<FlacMetadataBlock.Unknown>(blocks[2])
            assertEquals(unknown.type, (blocks[2] as FlacMetadataBlock.Unknown).unknown.type)
            assertContentEquals(unknown.data, (blocks[2] as FlacMetadataBlock.Unknown).unknown.data)
            assertIs<FlacMetadataBlock.VorbisComment>(blocks[3])
            assertEquals("ordered-vendor", (blocks[3] as FlacMetadataBlock.VorbisComment).comment.vendor)
            assertEquals(listOf("Ordered"), (blocks[3] as FlacMetadataBlock.VorbisComment).comment.comments["TITLE"])
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private fun deterministicPcm(frames: Int, format: FlacAudioFormat, frameOffset: Int = 0): IntArray {
        val minSample = -(1 shl (format.bitsPerSample - 1))
        val range = 1 shl format.bitsPerSample
        return IntArray(frames * format.channels) { sampleIndex ->
            val frame = (sampleIndex / format.channels) + frameOffset
            val channel = sampleIndex % format.channels
            ((frame * 97 + channel * 151) % range) + minSample
        }
    }
}
