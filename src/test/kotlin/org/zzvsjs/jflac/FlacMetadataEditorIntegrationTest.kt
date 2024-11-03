package org.zzvsjs.jflac

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FlacMetadataEditorIntegrationTest {
    @Test
    fun editUpdatesVorbisCommentsWithoutChangingAudioFrames() {
        val frames = 21
        val format = FlacAudioFormat(
            sampleRate = 44_100,
            channels = 2,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val samples = deterministicPcm(frames, format)
        val application = FlacApplicationBlock(
            id = byteArrayOf('J'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), 'C'.code.toByte()),
            data = byteArrayOf(0x01, 0x02, 0x03)
        )
        val output = Files.createTempFile("jflac-metadata-edit", ".flac")

        try {
            FlacEncoder().encode(
                output = output,
                format = format,
                samples = samples,
                metadata = FlacEncodingMetadata(
                    comments = mapOf("TITLE" to listOf("Before")),
                    applicationBlocks = listOf(application),
                    paddingBlocks = listOf(FlacPaddingBlock(length = 256))
                )
            )
            val decodedBefore = FlacDecoder().decode(output)

            FlacMetadataEditor().edit(output) { session ->
                session.setVorbisComments(
                    mapOf(
                        "TITLE" to listOf("After"),
                        "ARTIST" to listOf("Example Artist")
                    )
                )
            }

            val metadataAfter = FlacMetadataReader().read(output)
            val decodedAfter = FlacDecoder().decode(output)

            assertEquals(listOf("After"), metadataAfter.vorbisComment?.comments?.get("TITLE"))
            assertEquals(listOf("Example Artist"), metadataAfter.vorbisComment?.comments?.get("ARTIST"))
            assertEquals(1, metadataAfter.applicationBlocks.size)
            assertContentEquals(application.id, metadataAfter.applicationBlocks.single().id)
            assertContentEquals(application.data, metadataAfter.applicationBlocks.single().data)
            assertContentEquals(decodedBefore.interleavedSamples, decodedAfter.interleavedSamples)
            assertEquals(decodedBefore.streamInfo, decodedAfter.streamInfo)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun writeBlocksReplacesNonStreamInfoBlocksInCallerOrder() {
        val frames = 12
        val format = FlacAudioFormat(
            sampleRate = 32_000,
            channels = 1,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val samples = deterministicPcm(frames, format)
        val padding = FlacPaddingBlock(length = 11)
        val application = FlacApplicationBlock(
            id = byteArrayOf('E'.code.toByte(), 'D'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte()),
            data = byteArrayOf(0x10, 0x20)
        )
        val comment = FlacVorbisComment(
            vendor = "ignored",
            comments = mapOf("TITLE" to listOf("Edited order"))
        )
        val output = Files.createTempFile("jflac-metadata-block-edit", ".flac")

        try {
            FlacEncoder().encode(
                output = output,
                format = format,
                samples = samples,
                metadata = FlacEncodingMetadata(comments = mapOf("TITLE" to listOf("Original")))
            )
            val decodedBefore = FlacDecoder().decode(output)

            FlacMetadataEditor().writeBlocks(
                output,
                listOf(
                    FlacMetadataBlock.Padding(padding),
                    FlacMetadataBlock.Application(application),
                    FlacMetadataBlock.VorbisComment(comment)
                )
            )

            val blocks = FlacMetadataReader().read(output).blocks
            val decodedAfter = FlacDecoder().decode(output)

            assertIs<FlacMetadataBlock.Padding>(blocks[0])
            assertEquals(padding, (blocks[0] as FlacMetadataBlock.Padding).padding)
            assertIs<FlacMetadataBlock.Application>(blocks[1])
            assertContentEquals(application.id, (blocks[1] as FlacMetadataBlock.Application).application.id)
            assertContentEquals(application.data, (blocks[1] as FlacMetadataBlock.Application).application.data)
            assertIs<FlacMetadataBlock.VorbisComment>(blocks[2])
            assertEquals(listOf("Edited order"), (blocks[2] as FlacMetadataBlock.VorbisComment).comment.comments["TITLE"])
            assertContentEquals(decodedBefore.interleavedSamples, decodedAfter.interleavedSamples)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private fun deterministicPcm(frames: Int, format: FlacAudioFormat): IntArray {
        val minSample = -(1 shl (format.bitsPerSample - 1))
        val range = 1 shl format.bitsPerSample
        return IntArray(frames * format.channels) { sampleIndex ->
            val frame = sampleIndex / format.channels
            val channel = sampleIndex % format.channels
            ((frame * 89 + channel * 131) % range) + minSample
        }
    }
}
