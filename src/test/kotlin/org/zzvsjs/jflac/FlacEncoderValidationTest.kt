package org.zzvsjs.jflac

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FlacEncoderValidationTest {
    private val validFormat = FlacAudioFormat(
        sampleRate = 44_100,
        channels = 1,
        bitsPerSample = 16
    )

    @Test
    fun rejectsEncoderThreadCountsOutsideFlacLimits() {
        assertFailsWith<IllegalArgumentException> {
            validateFlacEncodingOptions(FlacEncodingOptions(numThreads = 0), validFormat)
        }

        assertFailsWith<IllegalArgumentException> {
            validateFlacEncodingOptions(FlacEncodingOptions(numThreads = 129), validFormat)
        }
    }

    @Test
    fun invalidSampleRatesUseArgumentErrorsAcrossEncoderEntrypoints() {
        val cases = listOf(
            InvalidEncoderConfiguration(
                format = validFormat.copy(sampleRate = 0),
                options = FlacEncodingOptions(),
                expectedMessage = "Sample rate must be between 1 and 1048575 Hz."
            ),
            InvalidEncoderConfiguration(
                format = validFormat.copy(sampleRate = Int.MAX_VALUE),
                options = FlacEncodingOptions(),
                expectedMessage = "Sample rate must be between 1 and 1048575 Hz."
            ),
            InvalidEncoderConfiguration(
                format = validFormat.copy(sampleRate = 123_456),
                options = FlacEncodingOptions(streamableSubset = true),
                expectedMessage = "Sample rate 123456 Hz is not permitted by the FLAC streamable subset."
            )
        )

        cases.forEach(::assertAllEncoderEntrypointsReject)
    }

    @Test
    fun invalidBlockSizesUseArgumentErrorsAcrossEncoderEntrypoints() {
        val cases = listOf(
            InvalidEncoderConfiguration(
                format = validFormat,
                options = FlacEncodingOptions(blockSize = 0),
                expectedMessage = "Block size must be between 16 and 65535 samples."
            ),
            InvalidEncoderConfiguration(
                format = validFormat,
                options = FlacEncodingOptions(blockSize = 15),
                expectedMessage = "Block size must be between 16 and 65535 samples."
            ),
            InvalidEncoderConfiguration(
                format = validFormat,
                options = FlacEncodingOptions(blockSize = 65_536),
                expectedMessage = "Block size must be between 16 and 65535 samples."
            ),
            InvalidEncoderConfiguration(
                format = validFormat,
                options = FlacEncodingOptions(blockSize = 4_609, streamableSubset = true),
                expectedMessage = "Block size 4609 is not permitted by the FLAC streamable subset at 44100 Hz."
            )
        )

        cases.forEach(::assertAllEncoderEntrypointsReject)
    }

    @Test
    fun nonSubsetConfigurationAcceptsLegalExtendedValues() {
        val format = validFormat.copy(sampleRate = 123_456)
        val options = FlacEncodingOptions(
            blockSize = 65_535,
            streamableSubset = false
        )

        validateFlacAudioFormat(format)
        validateFlacEncodingOptions(options, format)
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

    /**
     * Exercises validation through both public encoding styles, every output
     * transport, and both containers. Each call must fail before it can create
     * a native encoder session or write any output bytes.
     */
    private fun assertAllEncoderEntrypointsReject(configuration: InvalidEncoderConfiguration) {
        val outputPath = Files.createTempFile("jflac-invalid-encoder-configuration", ".flac")

        try {
            FlacEncodingContainer.entries.forEach { container ->
                val options = configuration.options.copy(container = container)
                val encoder = FlacEncoder()

                assertArgumentFailure("$container path session", configuration.expectedMessage) {
                    encoder.open(outputPath, configuration.format, options = options).use { }
                }

                assertArgumentFailure("$container path one-shot", configuration.expectedMessage) {
                    encoder.encode(outputPath, configuration.format, IntArray(0), options = options)
                }

                assertArgumentFailure("$container stream session", configuration.expectedMessage) {
                    encoder.open(ByteArrayOutputStream(), configuration.format, options = options).use { }
                }

                assertArgumentFailure("$container stream one-shot", configuration.expectedMessage) {
                    encoder.encode(ByteArrayOutputStream(), configuration.format, IntArray(0), options = options)
                }

                Files.newByteChannel(
                    outputPath,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE
                ).use { channel ->
                    assertArgumentFailure("$container channel session", configuration.expectedMessage) {
                        encoder.open(channel, configuration.format, options = options).use { }
                    }

                    assertArgumentFailure("$container channel one-shot", configuration.expectedMessage) {
                        encoder.encode(channel, configuration.format, IntArray(0), options = options)
                    }
                }
            }
        } finally {
            Files.deleteIfExists(outputPath)
        }
    }

    private fun assertArgumentFailure(label: String, expectedMessage: String, block: () -> Unit) {
        val failure = assertFailsWith<IllegalArgumentException>(label, block)

        assertEquals(expectedMessage, failure.message, label)
    }

    private data class InvalidEncoderConfiguration(
        val format: FlacAudioFormat,
        val options: FlacEncodingOptions,
        val expectedMessage: String
    )

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
