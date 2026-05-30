package org.zzvsjs.jflac

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FlacStreamIoIntegrationTest {
    @Test
    fun inputStreamDecodeMatchesFileDecode() {
        val frames = 19
        val format = FlacAudioFormat(
            sampleRate = 44_100,
            channels = 2,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val samples = deterministicPcm(frames, format)
        val file = Files.createTempFile("jflac-input-stream-decode", ".flac")

        try {
            FlacEncoder().encode(file, format, samples)

            val fromFile = FlacDecoder().decode(file)
            val fromStream = Files.newInputStream(file).use { input ->
                FlacDecoder().decode(input)
            }

            assertEquals(fromFile.streamInfo, fromStream.streamInfo)
            assertEquals(fromFile.totalFrames, fromStream.totalFrames)
            assertContentEquals(fromFile.interleavedSamples, fromStream.interleavedSamples)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun inputStreamDecodeInterleavedAndChannelsForwardChunks() {
        val frames = 23
        val format = FlacAudioFormat(
            sampleRate = 32_000,
            channels = 2,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val samples = deterministicPcm(frames, format)
        val flacBytes = encodeFileBytes(format, samples)
        val interleavedChunks = ArrayList<FlacInterleavedPcmChunk>()
        val channelChunks = ArrayList<FlacChannelPcmChunk>()

        val interleavedSummary = FlacDecoder().decodeInterleaved(
            input = ByteArrayInputStream(flacBytes),
            onChunk = FlacInterleavedPcmHandler { chunk -> interleavedChunks += chunk }
        )
        val channelSummary = FlacDecoder().decodeChannels(
            input = ByteArrayInputStream(flacBytes),
            onChunk = FlacChannelPcmHandler { chunk -> channelChunks += chunk }
        )

        assertEquals(frames.toLong(), interleavedSummary.totalFrames)
        assertEquals(interleavedSummary, channelSummary)
        assertContentEquals(samples, interleavedChunks.flatMap { it.interleavedSamples.asIterable() }.toIntArray())
        assertEquals(interleavedChunks.size, channelChunks.size)
        interleavedChunks.zip(channelChunks).forEach { (interleaved, channels) ->
            assertEquals(interleaved.streamInfo, channels.streamInfo)
            assertEquals(interleaved.frames, channels.frames)
            assertEquals(interleaved.firstFrameIndex, channels.firstFrameIndex)
            assertContentEquals(interleaved.interleavedSamples, interleave(channels.channelSamples, channels.frames))
        }
    }

    @Test
    fun inputStreamDecodeSupportsOggFlac() {
        val oggFile = createOggFlacFixture("jflac-ogg-input-stream")

        try {
            val decoded = FlacDecoder().decode(ByteArrayInputStream(Files.readAllBytes(oggFile)))

            assertEquals(8_000, decoded.streamInfo.sampleRate)
            assertEquals(1, decoded.streamInfo.channels)
            assertEquals(80, decoded.totalFrames)
        } finally {
            Files.deleteIfExists(oggFile)
        }
    }

    @Test
    fun seekableChannelDecodeSupportsWholeFileAndRange() {
        val frames = 47
        val format = FlacAudioFormat(
            sampleRate = 44_100,
            channels = 2,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val samples = deterministicPcm(frames, format)
        val file = Files.createTempFile("jflac-seekable-channel-decode", ".flac")

        try {
            FlacEncoder().encode(file, format, samples)

            val fullFromChannel = Files.newByteChannel(file, StandardOpenOption.READ).use { channel ->
                FlacDecoder().decode(channel)
            }
            val firstSample = 11L
            val maxFrames = 17L
            val rangeFromChannel = Files.newByteChannel(file, StandardOpenOption.READ).use { channel ->
                FlacDecoder().decode(channel, firstSample, maxFrames)
            }

            assertEquals(frames.toLong(), fullFromChannel.totalFrames)
            assertContentEquals(samples, fullFromChannel.interleavedSamples)
            assertEquals(maxFrames, rangeFromChannel.totalFrames)
            assertContentEquals(
                samples.copyOfRange(
                    Math.toIntExact(firstSample * format.channels),
                    Math.toIntExact((firstSample + maxFrames) * format.channels)
                ),
                rangeFromChannel.interleavedSamples
            )
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun seekableChannelDecodeSupportsOggFlac() {
        val oggFile = createOggFlacFixture("jflac-ogg-seekable-channel")

        try {
            Files.newByteChannel(oggFile, StandardOpenOption.READ).use { channel ->
                val decoded = FlacDecoder().decode(channel)

                assertEquals(8_000, decoded.streamInfo.sampleRate)
                assertEquals(1, decoded.streamInfo.channels)
                assertEquals(80, decoded.totalFrames)
            }
            Files.newByteChannel(oggFile, StandardOpenOption.READ).use { channel ->
                val range = FlacDecoder().decode(channel, firstSample = 5, maxFrames = 12)

                assertEquals(12L, range.totalFrames)
                assertEquals(12, range.interleavedSamples.size)
            }
            Files.newByteChannel(oggFile, StandardOpenOption.READ).use { channel ->
                FlacDecoder().open(channel).use { session ->
                    val chunks = ArrayList<FlacInterleavedPcmChunk>()
                    val summary = session.decodeInterleaved(
                        firstSample = 9,
                        maxFrames = 7,
                        onChunk = FlacInterleavedPcmHandler { chunk -> chunks += chunk }
                    )

                    assertEquals(7L, summary.totalFrames)
                    assertTrue(chunks.isNotEmpty())
                    assertEquals(9L, chunks.first().firstFrameIndex)
                    assertEquals(7, chunks.sumOf { chunk -> chunk.frames })
                }
            }
        } finally {
            Files.deleteIfExists(oggFile)
        }
    }

    @Test
    fun seekableChannelDecodeSessionReusesNativeDecoderForRanges() {
        val frames = 59
        val format = FlacAudioFormat(
            sampleRate = 48_000,
            channels = 2,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val samples = deterministicPcm(frames, format)
        val file = Files.createTempFile("jflac-seekable-channel-session", ".flac")

        try {
            FlacEncoder().encode(file, format, samples)

            Files.newByteChannel(file, StandardOpenOption.READ).use { channel ->
                FlacDecoder().open(channel).use { session ->
                    val firstRange = ArrayList<FlacInterleavedPcmChunk>()
                    val secondRange = ArrayList<FlacInterleavedPcmChunk>()

                    val firstSummary = session.decodeInterleaved(
                        firstSample = 7,
                        maxFrames = 9,
                        onChunk = FlacInterleavedPcmHandler { chunk -> firstRange += chunk }
                    )
                    val secondSummary = session.decodeInterleaved(
                        firstSample = 31,
                        maxFrames = 12,
                        onChunk = FlacInterleavedPcmHandler { chunk -> secondRange += chunk }
                    )

                    assertEquals(9, firstSummary.totalFrames)
                    assertEquals(12, secondSummary.totalFrames)
                    assertContentEquals(
                        samples.copyOfRange(7 * format.channels, (7 + 9) * format.channels),
                        firstRange.flatMap { it.interleavedSamples.asIterable() }.toIntArray()
                    )
                    assertContentEquals(
                        samples.copyOfRange(31 * format.channels, (31 + 12) * format.channels),
                        secondRange.flatMap { it.interleavedSamples.asIterable() }.toIntArray()
                    )
                }
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun outputStreamEncodeRoundTripsThroughInputStreamDecode() {
        val frames = 31
        val format = FlacAudioFormat(
            sampleRate = 48_000,
            channels = 2,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val samples = deterministicPcm(frames, format)
        val output = ByteArrayOutputStream()

        FlacEncoder().encode(output, format, samples)

        val decoded = FlacDecoder().decode(ByteArrayInputStream(output.toByteArray()))

        assertEquals(format.sampleRate, decoded.streamInfo.sampleRate)
        assertEquals(format.channels, decoded.streamInfo.channels)
        assertEquals(format.bitsPerSample, decoded.streamInfo.bitsPerSample)
        assertEquals(frames.toLong(), decoded.totalFrames)
        assertContentEquals(samples, decoded.interleavedSamples)
    }

    @Test
    fun outputStreamOpenWritesMultiplePcmChunks() {
        val firstFrames = 9
        val secondFrames = 13
        val format = FlacAudioFormat(
            sampleRate = 22_050,
            channels = 1,
            bitsPerSample = 16,
            totalSamplesEstimate = (firstFrames + secondFrames).toLong()
        )
        val firstChunk = deterministicPcm(firstFrames, format)
        val secondChunk = deterministicPcm(secondFrames, format, frameOffset = 500)
        val output = ByteArrayOutputStream()

        FlacEncoder().open(output, format).use { session ->
            session.writeInterleaved(firstChunk, firstFrames)
            session.writeInterleaved(IntArray(0), 0)
            session.writeInterleaved(secondChunk, secondFrames)
        }

        val decoded = FlacDecoder().decode(ByteArrayInputStream(output.toByteArray()))

        assertEquals((firstFrames + secondFrames).toLong(), decoded.totalFrames)
        assertContentEquals(firstChunk + secondChunk, decoded.interleavedSamples)
    }

    @Test
    fun seekableChannelEncodeBackPatchesStreamInfoWithoutSampleEstimate() {
        val frames = 37
        val format = FlacAudioFormat(
            sampleRate = 32_000,
            channels = 2,
            bitsPerSample = 16
        )
        val samples = deterministicPcm(frames, format)
        val output = Files.createTempFile("jflac-seekable-channel-encode", ".flac")

        try {
            Files.newByteChannel(
                output,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
            ).use { channel ->
                FlacEncoder().encode(channel, format, samples)
            }

            val metadata = FlacMetadataReader().read(output)
            val decoded = FlacDecoder().decode(output)

            assertEquals(frames.toLong(), metadata.streamInfo.totalSamples)
            assertEquals(frames.toLong(), decoded.totalFrames)
            assertContentEquals(samples, decoded.interleavedSamples)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun outputStreamEncodePropagatesWriteFailures() {
        val format = FlacAudioFormat(
            sampleRate = 44_100,
            channels = 1,
            bitsPerSample = 16,
            totalSamplesEstimate = 1
        )
        val output = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("single-byte writes are not expected")
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                throw IOException("simulated stream failure")
            }
        }

        assertFailsWith<IOException> {
            FlacEncoder().encode(output, format, intArrayOf(0))
        }
    }

    private fun encodeFileBytes(format: FlacAudioFormat, samples: IntArray): ByteArray {
        val file = Files.createTempFile("jflac-stream-fixture", ".flac")
        return try {
            FlacEncoder().encode(file, format, samples)
            Files.readAllBytes(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun interleave(channelSamples: List<IntArray>, frames: Int): IntArray {
        return IntArray(frames * channelSamples.size) { sampleIndex ->
            val frame = sampleIndex / channelSamples.size
            val channel = sampleIndex % channelSamples.size
            channelSamples[channel][frame]
        }
    }

    private fun deterministicPcm(frames: Int, format: FlacAudioFormat, frameOffset: Int = 0): IntArray {
        val minSample = -(1 shl (format.bitsPerSample - 1))
        val range = 1 shl format.bitsPerSample
        return IntArray(frames * format.channels) { sampleIndex ->
            val frame = (sampleIndex / format.channels) + frameOffset
            val channel = sampleIndex % format.channels
            ((frame * 83 + channel * 127) % range) + minSample
        }
    }
}
