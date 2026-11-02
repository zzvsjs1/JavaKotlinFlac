package org.zzvsjs.jflac

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for decode model invariants that do not need native libraries.
 */
class FlacDecodeModelTest {
    @Test
    fun unknownTotalCollectorAcceptsFewerFramesThanBound() {
        val decoded = completedCollector(totalSamples = 0, frames = 3).toDecodedAudio()

        assertEquals(3L, decoded.totalFrames)
        assertContentEquals(intArrayOf(0, 1, 2), decoded.interleavedSamples)
    }

    @Test
    fun unknownTotalCollectorAcceptsExactlyTheBound() {
        val decoded = completedCollector(totalSamples = 0, frames = 4).toDecodedAudio()

        assertEquals(4L, decoded.totalFrames)
        assertContentEquals(intArrayOf(0, 1, 2, 3), decoded.interleavedSamples)
    }

    @Test
    fun unknownTotalCollectorRejectsFramesAboveBound() {
        val consumer = completedCollector(totalSamples = 0, frames = 5)

        assertFailsWith<IllegalStateException> { consumer.toDecodedAudio() }
    }

    @Test
    fun unknownTotalCollectorAcceptsEmptyZeroFrameRange() {
        val decoded = completedCollector(totalSamples = 0, frames = 0, maxFrames = 0).toDecodedAudio()

        assertEquals(0L, decoded.totalFrames)
        assertContentEquals(IntArray(0), decoded.interleavedSamples)
    }

    @Test
    fun unknownTotalCollectorRejectsPcmForZeroFrameRange() {
        val consumer = completedCollector(totalSamples = 0, frames = 1, maxFrames = 0)

        assertFailsWith<IllegalStateException> { consumer.toDecodedAudio() }
    }

    @Test
    fun knownTotalCollectorAcceptsExactAvailableFramesBelowBound() {
        val decoded = completedCollector(totalSamples = 3, frames = 3).toDecodedAudio()

        assertEquals(3L, decoded.totalFrames)
    }

    @Test
    fun knownTotalCollectorRejectsMissingFramesBelowBound() {
        val consumer = completedCollector(totalSamples = 3, frames = 2)

        assertFailsWith<IllegalStateException> { consumer.toDecodedAudio() }
    }

    @Test
    fun decodeSummaryRejectsTotalSampleCountOverflow() {
        val info = FlacStreamInfo(
            sampleRate = 44100,
            channels = 2,
            bitsPerSample = 16,
            totalSamples = 0,
            minBlockSize = 16,
            maxBlockSize = 4096,
            minFrameSize = 0,
            maxFrameSize = 0
        )
        val summary = FlacDecodeSummary(
            streamInfo = info,
            totalFrames = Long.MAX_VALUE
        )

        assertFailsWith<ArithmeticException> {
            summary.totalSamples
        }
    }

    private fun completedCollector(totalSamples: Long, frames: Int, maxFrames: Long = 4): BufferingPcmConsumer {
        val consumer = BufferingPcmConsumer(firstFrameIndex = 0, maxFrames = maxFrames)
        consumer.onStreamInfo(FlacStreamInfo(8_000, 1, 16, totalSamples, 16, 4096, 0, 0))
        consumer.onPcmInterleaved(IntArray(frames) { it }, frames)
        consumer.onComplete()
        return consumer
    }
}
