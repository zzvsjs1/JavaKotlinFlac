package org.zzvsjs.jflac

import kotlin.test.Test
import kotlin.test.assertFailsWith

class FlacEncoderValidationTest {
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
