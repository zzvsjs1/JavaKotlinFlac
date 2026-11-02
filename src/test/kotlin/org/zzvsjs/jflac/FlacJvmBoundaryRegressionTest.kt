package org.zzvsjs.jflac

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FlacJvmBoundaryRegressionTest {
    @Test
    fun oggDecodeAcceptsOneByteChannelReads() = assertShortReadOggDecode(maxRead = 1)

    @Test
    fun oggDecodeAcceptsTwoByteChannelReads() = assertShortReadOggDecode(maxRead = 2)

    @Test
    fun oggPullDecodeAcceptsOneByteChannelReads() = assertShortReadOggPullDecode(maxRead = 1)

    @Test
    fun oggPullDecodeAcceptsTwoByteChannelReads() = assertShortReadOggPullDecode(maxRead = 2)

    @Test
    fun channelInspectionRestoresPositionAfterShortReads() {
        for (maxRead in listOf(1, 2)) {
            for (origin in listOf(0, 13)) {
                ReadLimitedChannel(ByteArray(origin) + "OggS".toByteArray(), maxRead).use { channel ->
                    channel.position(origin.toLong())

                    assertEquals(NativeFlacContainer.OGG, inspectNativeFlacChannel(channel))
                    assertEquals(origin.toLong(), channel.position())
                    assertTrue(channel.isOpen)
                }
            }
        }
    }

    @Test
    fun channelInspectionRejectsZeroProgressAndRestoresPosition() {
        ReadLimitedChannel(ByteArray(13) + "OggS".toByteArray(), maxRead = 0).use { channel ->
            channel.position(13)

            val error = assertFailsWith<FlacDecodeException> { inspectNativeFlacChannel(channel) }

            assertTrue(error.message.orEmpty().contains("progress", ignoreCase = true))
            assertEquals(13L, channel.position())
            assertTrue(channel.isOpen)
        }
    }

    @Test
    fun bufferedUnknownTotalRangeStopsAtActualEnd() {
        val samples = nativeSamples()
        ReadLimitedChannel(encodeNativeWithoutTotal(samples)).use { channel ->
            val decoded = FlacDecoder().decode(channel, firstSample = 0, maxFrames = 16_484)

            assertEquals(0L, decoded.streamInfo.totalSamples)
            assertEquals(16_384L, decoded.totalFrames)
            assertContentEquals(samples, decoded.interleavedSamples)
            assertTrue(channel.isOpen)
        }
    }

    @Test
    fun interleavedSummaryUnknownTotalRangeStopsAtActualEnd() {
        val samples = nativeSamples()
        val actual = ArrayList<Int>()
        ReadLimitedChannel(encodeNativeWithoutTotal(samples)).use { channel ->
            val summary = FlacDecoder().decodeInterleaved(
                channel, firstSample = 0, maxFrames = 16_484,
                onChunk = FlacInterleavedPcmHandler { actual.addAll(it.interleavedSamples.asIterable()) }
            )

            assertEquals(0L, summary.streamInfo.totalSamples)
            assertEquals(16_384L, summary.totalFrames)
            assertContentEquals(samples, actual.toIntArray())
            assertTrue(channel.isOpen)
        }
    }

    @Test
    fun channelSummaryUnknownTotalRangeStopsAtActualEnd() {
        val samples = nativeSamples()
        val actual = ArrayList<Int>()
        ReadLimitedChannel(encodeNativeWithoutTotal(samples)).use { channel ->
            val summary = FlacDecoder().decodeChannels(
                channel, firstSample = 0, maxFrames = 16_484,
                onChunk = FlacChannelPcmHandler { actual.addAll(it.channelSamples.single().asIterable()) }
            )

            assertEquals(0L, summary.streamInfo.totalSamples)
            assertEquals(16_384L, summary.totalFrames)
            assertContentEquals(samples, actual.toIntArray())
        }
    }

    @Test
    fun reusableUnknownTotalRangeStopsAtActualEnd() {
        val samples = nativeSamples()
        val actual = ArrayList<Int>()
        ReadLimitedChannel(encodeNativeWithoutTotal(samples)).use { channel ->
            FlacDecoder().open(channel).use { session ->
                val summary = session.decodeInterleaved(
                    firstSample = 0, maxFrames = 16_484,
                    onChunk = FlacInterleavedPcmHandler { actual.addAll(it.interleavedSamples.asIterable()) }
                )

                assertEquals(0L, summary.streamInfo.totalSamples)
                assertEquals(16_384L, summary.totalFrames)
                assertContentEquals(samples, actual.toIntArray())
            }

            assertTrue(channel.isOpen)
        }
    }

    @Test
    fun unknownTotalZeroFrameRangesStayEmpty() {
        val bytes = encodeNativeWithoutTotal(nativeSamples())
        ReadLimitedChannel(bytes).use { channel ->
            val decoded = FlacDecoder().decode(channel, firstSample = 0, maxFrames = 0)

            assertEquals(0L, decoded.totalFrames)
            assertContentEquals(IntArray(0), decoded.interleavedSamples)
        }

        ReadLimitedChannel(bytes).use { channel ->
            FlacDecoder().open(channel).use { session ->
                val summary = session.decodeInterleaved(
                    firstSample = 0, maxFrames = 0,
                    onChunk = FlacInterleavedPcmHandler { error("A zero-frame range must not emit PCM.") }
                )

                assertEquals(0L, summary.totalFrames)
            }
        }
    }

    @Test
    fun bufferedKnownTotalRangeStillRejectsMissingFrames() {
        val consumer = BufferingPcmConsumer(firstFrameIndex = 0, maxFrames = 4)
        consumer.onStreamInfo(FlacStreamInfo(8_000, 1, 16, 3, 16, 4096, 0, 0))
        consumer.onPcmInterleaved(intArrayOf(11, 12), frames = 2)
        consumer.onComplete()

        assertFailsWith<IllegalStateException> { consumer.toDecodedAudio() }
    }

    private fun assertShortReadOggDecode(maxRead: Int) {
        val samples = IntArray(80) { it * 17 - 500 }
        val bytes = encodeOgg(samples)
        for (origin in listOf(0, 13)) {
            ReadLimitedChannel(ByteArray(origin) + bytes, maxRead).use { channel ->
                channel.position(origin.toLong())
                val decoded = FlacDecoder().decode(channel)

                assertEquals(80L, decoded.totalFrames)
                assertContentEquals(samples, decoded.interleavedSamples)
                assertTrue(channel.isOpen)
            }
        }
    }

    private fun assertShortReadOggPullDecode(maxRead: Int) {
        val samples = IntArray(80) { it * 17 - 500 }
        val bytes = encodeOgg(samples)
        for (origin in listOf(0, 13)) {
            ReadLimitedChannel(ByteArray(origin) + bytes, maxRead).use { channel ->
                channel.position(origin.toLong())
                val actual = ArrayList<Int>()
                FlacDecoder().openPull(channel).use { session ->
                    val chunk = IntArray(7)
                    while (true) {
                        val frames = session.readInterleaved(chunk, 7)
                        if (frames == -1) {
                            break
                        }

                        actual.addAll(chunk.take(frames))
                    }
                }

                assertEquals(80, actual.size)
                assertContentEquals(samples, actual.toIntArray())
                assertTrue(channel.isOpen)
            }
        }
    }

    private fun encodeOgg(samples: IntArray): ByteArray = ByteArrayOutputStream().also { output ->
        FlacEncoder().encode(
            output, FlacAudioFormat(8_000, 1, 16, samples.size.toLong()), samples,
            options = FlacEncodingOptions(container = FlacEncodingContainer.OGG, oggSerialNumber = 123)
        )
    }.toByteArray()

    private fun nativeSamples(): IntArray = IntArray(16_384) { it % 1000 - 500 }

    private fun encodeNativeWithoutTotal(samples: IntArray): ByteArray = ByteArrayOutputStream().also { output ->
        FlacEncoder().encode(output, FlacAudioFormat(8_000, 1, 16), samples)
    }.toByteArray()

    private class ReadLimitedChannel(private val bytes: ByteArray, private val maxRead: Int = Int.MAX_VALUE) : SeekableByteChannel {
        private var offset = 0
        private var open = true
        private var zeroReads = 0

        override fun read(target: ByteBuffer): Int {
            if (!target.hasRemaining()) return 0
            if (offset >= bytes.size) return -1

            // Bound a broken zero-progress retry loop so this regression fails instead of hanging the suite.
            if (maxRead == 0) {
                check(++zeroReads <= 8) { "Channel inspection repeatedly retried a read without progress." }
                return 0
            }

            val count = minOf(maxRead, target.remaining(), bytes.size - offset)
            target.put(bytes, offset, count)
            offset += count
            return count
        }

        override fun position(): Long = offset.toLong()
        override fun position(newPosition: Long): SeekableByteChannel = apply {
            require(newPosition >= 0)
            offset = Math.toIntExact(newPosition)
        }

        override fun size(): Long = bytes.size.toLong()
        override fun isOpen(): Boolean = open
        override fun close() { open = false }
        override fun write(source: ByteBuffer): Int = throw NonWritableChannelException()
        override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
    }
}
