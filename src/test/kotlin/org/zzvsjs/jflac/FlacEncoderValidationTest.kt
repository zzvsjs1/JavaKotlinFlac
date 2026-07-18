package org.zzvsjs.jflac

import kotlin.test.Test
import kotlin.test.assertFailsWith

class FlacEncoderValidationTest {
    @Test
    fun rejectsEncoderThreadCountsOutsideFlacLimits() {
        assertFailsWith<IllegalArgumentException> {
            validateFlacEncodingOptions(FlacEncodingOptions(numThreads = 0))
        }
        assertFailsWith<IllegalArgumentException> {
            validateFlacEncodingOptions(FlacEncodingOptions(numThreads = 129))
        }
    }

    @Test
    fun rejectsOversizedApplicationMetadataBlocks() {
        val largestInvalidPayload = ByteArray(0xFF_FFFF - 4 + 1)

        assertFailsWith<IllegalArgumentException> {
            validateFlacEncodingMetadata(
                FlacEncodingMetadata(
                    applicationBlocks = listOf(
                        FlacApplicationBlock(
                            id = byteArrayOf('J'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), 'C'.code.toByte()),
                            data = largestInvalidPayload
                        )
                    )
                )
            )
        }
    }

    @Test
    fun rejectsVorbisCommentsWithEmbeddedNulCharacters() {
        assertFailsWith<IllegalArgumentException> {
            validateFlacEncodingMetadata(
                FlacEncodingMetadata(
                    comments = mapOf("TITLE" to listOf("Bad\u0000Value"))
                )
            )
        }
    }

    @Test
    fun rejectsMultipleEncodedSeekTables() {
        val metadata = FlacEncodingMetadata(
            seekTables = listOf(
                FlacSeekTable(emptyList()),
                FlacSeekTable(emptyList())
            )
        )

        assertFailsWith<IllegalArgumentException> {
            validateFlacEncodingMetadata(metadata)
        }
    }

    @Test
    fun rejectsInvalidEncodedSeekPoints() {
        assertFailsWith<IllegalArgumentException> {
            validateFlacEncodingMetadata(
                FlacEncodingMetadata(
                    seekTables = listOf(
                        FlacSeekTable(
                            points = listOf(
                                FlacSeekPoint(sampleNumber = 0, streamOffset = 0, frameSamples = -1)
                            )
                        )
                    )
                )
            )
        }
    }

    @Test
    fun rejectsMultipleOrderedSeekTables() {
        assertFailsWith<IllegalArgumentException> {
            validateFlacEncodingMetadata(
                FlacEncodingMetadata(
                    blocks = listOf(
                        FlacMetadataBlock.SeekTable(FlacSeekTable(emptyList())),
                        FlacMetadataBlock.SeekTable(FlacSeekTable(emptyList()))
                    )
                )
            )
        }
    }

    @Test
    fun rejectsInvalidOrderedVorbisComments() {
        assertFailsWith<IllegalArgumentException> {
            validateFlacEncodingMetadata(
                FlacEncodingMetadata(
                    blocks = listOf(
                        FlacMetadataBlock.VorbisComment(
                            FlacVorbisComment(
                                vendor = "test",
                                comments = mapOf("" to listOf("value"))
                            )
                        )
                    )
                )
            )
        }
    }

    @Test
    fun orderedMetadataIgnoresGroupedConvenienceFields() {
        validateFlacEncodingMetadata(
            FlacEncodingMetadata(
                comments = mapOf("" to listOf("ignored")),
                seekTables = listOf(
                    FlacSeekTable(emptyList()),
                    FlacSeekTable(emptyList())
                ),
                blocks = listOf(
                    FlacMetadataBlock.Padding(FlacPaddingBlock(length = 0))
                )
            )
        )
    }

    @Test
    fun rejectsInvalidEncodedCueSheetFields() {
        assertFailsWith<IllegalArgumentException> {
            validateFlacEncodingMetadata(
                FlacEncodingMetadata(
                    cueSheets = listOf(
                        validCueSheet().copy(
                            mediaCatalogNumber = "x".repeat(129)
                        )
                    )
                )
            )
        }

        assertFailsWith<IllegalArgumentException> {
            validateFlacEncodingMetadata(
                FlacEncodingMetadata(
                    cueSheets = listOf(
                        validCueSheet().copy(
                            tracks = listOf(
                                validCueSheet().tracks.first().copy(isrc = "x".repeat(13))
                            )
                        )
                    )
                )
            )
        }
    }

    @Test
    fun rejectsInvalidRawMetadataBlocks() {
        assertFailsWith<IllegalArgumentException> {
            FlacPaddingBlock(length = -1)
        }

        assertFailsWith<IllegalArgumentException> {
            FlacUnknownMetadataBlock(type = 6, data = byteArrayOf(0x01))
        }

        assertFailsWith<IllegalArgumentException> {
            FlacUnknownMetadataBlock(type = 127, data = byteArrayOf(0x01))
        }
    }

    private fun validCueSheet(): FlacCueSheet {
        return FlacCueSheet(
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
                )
            )
        )
    }
}
