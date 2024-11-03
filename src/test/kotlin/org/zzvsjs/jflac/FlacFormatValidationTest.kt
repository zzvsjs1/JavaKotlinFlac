package org.zzvsjs.jflac

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlacFormatValidationTest {
    @Test
    fun sampleRateChecksUseLibFlacRules() {
        assertTrue(FlacFormat.isSampleRateValid(44_100))
        assertFalse(FlacFormat.isSampleRateValid(Int.MAX_VALUE))

        assertTrue(FlacFormat.isSampleRateSubset(44_100))
        assertFalse(FlacFormat.isSampleRateSubset(123_456))
    }

    @Test
    fun blockSizeSubsetCheckUsesSampleRateContext() {
        assertTrue(FlacFormat.isBlockSizeSubset(blockSize = 4096, sampleRate = 44_100))
        assertFalse(FlacFormat.isBlockSizeSubset(blockSize = 65_535, sampleRate = 44_100))
    }

    @Test
    fun vorbisCommentChecksRejectMalformedEntries() {
        assertTrue(FlacFormat.isVorbisCommentNameLegal("TITLE"))
        assertFalse(FlacFormat.isVorbisCommentNameLegal("BAD\u001fKEY"))
        assertFalse(FlacFormat.isVorbisCommentNameLegal("BAD=KEY"))

        assertTrue(FlacFormat.isVorbisCommentValueLegal("Round trip"))

        assertTrue(FlacFormat.isVorbisCommentEntryLegal("TITLE=Round trip"))
        assertFalse(FlacFormat.isVorbisCommentEntryLegal("TITLE"))
    }

    @Test
    fun pictureValidationReportsLibFlacViolations() {
        val validPicture = FlacPicture(
            type = 3,
            mimeType = "image/png",
            description = "Cover",
            width = 1,
            height = 1,
            depth = 24,
            colors = 0,
            data = byteArrayOf(0x01, 0x23)
        )
        val invalidPicture = validPicture.copy(mimeType = "image/png\n")

        assertNull(FlacFormat.pictureViolation(validPicture))
        assertTrue(FlacFormat.pictureViolation(invalidPicture)?.isNotBlank() == true)
    }
}
