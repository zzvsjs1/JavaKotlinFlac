package org.zzvsjs.jflac

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Unit tests for decode model invariants that do not need native libraries.
 */
class FlacDecodeModelTest {
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
}
