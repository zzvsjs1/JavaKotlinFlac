package org.zzvsjs.jflac

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FlacPullDecoderTest {
    @Test
    fun filePullSessionReadsSamePcmAsWholeFileDecode() {
        val frames = 53
        val format = FlacAudioFormat(
            sampleRate = 44_100,
            channels = 2,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val samples = deterministicPcm(frames, format)
        val file = Files.createTempFile("jflac-pull-file", ".flac")

        try {
            FlacEncoder().encode(file, format, samples)

            FlacDecoder().openPull(file).use { session ->
                assertEquals(format.sampleRate, session.streamInfo.sampleRate)
                assertEquals(format.channels, session.streamInfo.channels)
                assertEquals(format.bitsPerSample, session.streamInfo.bitsPerSample)

                val output = IntArray(frames * format.channels)
                var outputOffset = 0
                val chunk = IntArray(7 * format.channels)

                while (true) {
                    val readFrames = session.readInterleaved(chunk, 7)
                    if (readFrames == -1) {
                        break
                    }

                    val readSamples = readFrames * format.channels
                    chunk.copyInto(output, outputOffset, 0, readSamples)
                    outputOffset += readSamples
                }

                assertEquals(samples.size, outputOffset)
                assertContentEquals(samples, output)
                assertEquals(-1, session.readInterleaved(chunk, 7))
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun streamPullSessionSupportsZeroFrameReadAndSmallBuffers() {
        val frames = 19
        val format = FlacAudioFormat(
            sampleRate = 22_050,
            channels = 1,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val samples = deterministicPcm(frames, format)
        val file = Files.createTempFile("jflac-pull-stream", ".flac")

        try {
            FlacEncoder().encode(file, format, samples)
            val bytes = Files.readAllBytes(file)

            FlacDecoder().openPull(ByteArrayInputStream(bytes)).use { session ->
                val oneFrame = IntArray(format.channels)
                assertEquals(0, session.readInterleaved(oneFrame, 0))

                val output = IntArray(frames * format.channels)
                var frameOffset = 0
                while (true) {
                    val readFrames = session.readInterleaved(oneFrame, 1)
                    if (readFrames == -1) {
                        break
                    }

                    oneFrame.copyInto(output, frameOffset * format.channels, 0, readFrames * format.channels)
                    frameOffset += readFrames
                }

                assertEquals(frames, frameOffset)
                assertContentEquals(samples, output)
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun seekableChannelPullSessionReadsSamePcmAsWholeFileDecode() {
        val frames = 37
        val format = FlacAudioFormat(
            sampleRate = 48_000,
            channels = 2,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val samples = deterministicPcm(frames, format)
        val file = Files.createTempFile("jflac-pull-channel", ".flac")

        try {
            FlacEncoder().encode(file, format, samples)

            Files.newByteChannel(file).use { channel ->
                FlacDecoder().openPull(channel).use { session ->
                    val output = IntArray(samples.size)
                    var offset = 0
                    val chunk = IntArray(5 * format.channels)
                    while (true) {
                        val readFrames = session.readInterleaved(chunk, 5)
                        if (readFrames == -1) {
                            break
                        }

                        val readSamples = readFrames * format.channels
                        chunk.copyInto(output, offset, 0, readSamples)
                        offset += readSamples
                    }

                    assertEquals(samples.size, offset)
                    assertContentEquals(samples, output)
                }
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun pullSessionSupportsOggFlacInputStream() {
        val oggFile = createOggFlacFixture("jflac-pull-ogg")

        try {
            val expectedSamples = FlacDecoder().decode(oggFile).interleavedSamples

            FlacDecoder().openPull(ByteArrayInputStream(Files.readAllBytes(oggFile))).use { session ->
                val output = IntArray(80)
                var offset = 0
                val chunk = IntArray(3)
                while (true) {
                    val readFrames = session.readInterleaved(chunk, 3)
                    if (readFrames == -1) {
                        break
                    }

                    chunk.copyInto(output, offset, 0, readFrames)
                    offset += readFrames
                }

                assertEquals(80, offset)
                assertContentEquals(expectedSamples, output)
                assertEquals(8_000, session.streamInfo.sampleRate)
                assertEquals(1, session.streamInfo.channels)
            }
        } finally {
            Files.deleteIfExists(oggFile)
        }
    }

    @Test
    fun pullSessionRejectsTooSmallOutputBuffer() {
        val frames = 8
        val format = FlacAudioFormat(
            sampleRate = 8_000,
            channels = 2,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val file = Files.createTempFile("jflac-pull-short-buffer", ".flac")

        try {
            FlacEncoder().encode(file, format, deterministicPcm(frames, format))

            FlacDecoder().openPull(file).use { session ->
                val error = assertFailsWith<IllegalArgumentException> {
                    session.readInterleaved(IntArray(1), 1)
                }

                assertEquals("Interleaved sample buffer must fit maxFrames * channels.", error.message)
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun streamPullSessionBecomesInactiveAfterReadFailure() {
        val frames = 32
        val format = FlacAudioFormat(
            sampleRate = 16_000,
            channels = 1,
            bitsPerSample = 16,
            totalSamplesEstimate = frames.toLong()
        )
        val file = Files.createTempFile("jflac-pull-failing-stream", ".flac")

        try {
            FlacEncoder().encode(file, format, deterministicPcm(frames, format))
            val input = FailingAfterOpenInputStream(Files.readAllBytes(file))

            FlacDecoder().openPull(input).use { session ->
                input.failReads = true
                val output = IntArray(format.channels)

                assertFailsWith<IOException> {
                    session.readInterleaved(output, 1)
                }

                val inactive = assertFailsWith<IllegalStateException> {
                    session.readInterleaved(output, 1)
                }

                assertEquals("The FLAC pull decoding session is no longer active.", inactive.message)
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun deterministicPcm(frames: Int, format: FlacAudioFormat): IntArray {
        val minSample = -(1 shl (format.bitsPerSample - 1))
        val range = 1 shl format.bitsPerSample
        return IntArray(frames * format.channels) { sampleIndex ->
            val frame = sampleIndex / format.channels
            val channel = sampleIndex % format.channels
            ((frame * 83 + channel * 127) % range) + minSample
        }
    }

    private class FailingAfterOpenInputStream(private val bytes: ByteArray) : InputStream() {
        var failReads: Boolean = false
        private var position: Int = 0
        private var mark: Int = 0

        override fun read(): Int {
            if (failReads) {
                throw IOException("simulated PCM read failure")
            }

            if (position >= bytes.size) {
                return -1
            }

            return bytes[position++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && length <= buffer.size - offset)
            if (failReads) {
                throw IOException("simulated PCM read failure")
            }

            if (length == 0) {
                return 0
            }

            if (position >= bytes.size) {
                return -1
            }

            /*
             * Feed libFLAC one byte per callback while openPull reads metadata.
             * That keeps metadata processing from pre-buffering audio data, so
             * the first PCM pull must call back into this stream and observe the
             * simulated source failure.
             */
            buffer[offset] = bytes[position++]
            return 1
        }

        override fun mark(readlimit: Int) {
            mark = position
        }

        override fun reset() {
            position = mark
        }

        override fun markSupported(): Boolean = true
    }
}
