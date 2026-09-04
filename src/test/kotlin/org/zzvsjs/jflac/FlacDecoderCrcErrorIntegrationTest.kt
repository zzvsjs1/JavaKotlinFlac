package org.zzvsjs.jflac

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val CRC_FIXTURE_BLOCK_FRAMES = 64
private const val CRC_FIXTURE_FRAMES = CRC_FIXTURE_BLOCK_FRAMES * 2
private const val CRC_FIXTURE_CHANNELS = 2
private const val FLAC_MARKER_LENGTH = 4
private const val FLAC_METADATA_HEADER_LENGTH = 4
private const val FLAC_FRAME_CRC_LENGTH = 2
private const val FLAC_FRAME_CRC16_POLYNOMIAL = 0x8005

/** Verifies that every public decoder transport surfaces native frame corruption. */
class FlacDecoderCrcErrorIntegrationTest {
    @Test
    fun wholeStreamAndRangeDecodersPropagateFrameCrcMismatch() {
        withCorruptedFrameFixture { path, bytes ->
            assertFrameCrcMismatch("file whole-stream decode") {
                FlacDecoder().decode(path)
            }

            assertFrameCrcMismatch("InputStream whole-stream decode") {
                ByteArrayInputStream(bytes).use { input ->
                    FlacDecoder().decode(input)
                }
            }

            assertFrameCrcMismatch("SeekableByteChannel whole-stream decode") {
                Files.newByteChannel(path).use { input ->
                    FlacDecoder().decode(input)
                }
            }

            assertFrameCrcMismatch("file range decode") {
                FlacDecoder().decode(path, firstSample = 0, maxFrames = CRC_FIXTURE_FRAMES.toLong())
            }

            assertFrameCrcMismatch("SeekableByteChannel range decode") {
                Files.newByteChannel(path).use { input ->
                    FlacDecoder().decode(
                        input = input,
                        firstSample = 0,
                        maxFrames = CRC_FIXTURE_FRAMES.toLong()
                    )
                }
            }
        }
    }

    @Test
    fun reusableDecodersPropagateFrameCrcMismatch() {
        withCorruptedFrameFixture { path, _ ->
            /* Exercise the unbounded reusable-session process-until-EOF path. */
            assertFrameCrcMismatch("file reusable decode to end") {
                FlacDecoder().open(path).use { session ->
                    session.decodeInterleaved(
                        firstSample = 0,
                        onChunk = FlacInterleavedPcmHandler { }
                    )
                }
            }

            /* Exercise its frame-at-a-time bounded range path independently. */
            assertFrameCrcMismatch("file reusable range decode") {
                FlacDecoder().open(path).use { session ->
                    session.decodeInterleaved(
                        firstSample = 0,
                        maxFrames = CRC_FIXTURE_FRAMES.toLong(),
                        onChunk = FlacInterleavedPcmHandler { }
                    )
                }
            }

            assertFrameCrcMismatch("SeekableByteChannel reusable decode") {
                Files.newByteChannel(path).use { input ->
                    FlacDecoder().open(input).use { session ->
                        session.decodeInterleaved(
                            firstSample = 0,
                            onChunk = FlacInterleavedPcmHandler { }
                        )
                    }
                }
            }
        }
    }

    @Test
    fun pullDecodersPropagateFrameCrcMismatchOnTheReadThatSeesIt() {
        withCorruptedFrameFixture { path, bytes ->
            assertFrameCrcMismatch("file pull decode") {
                FlacDecoder().openPull(path).use { session ->
                    session.readInterleaved(
                        IntArray(CRC_FIXTURE_FRAMES * CRC_FIXTURE_CHANNELS),
                        CRC_FIXTURE_FRAMES
                    )
                }
            }

            assertFrameCrcMismatch("InputStream pull decode") {
                ByteArrayInputStream(bytes).use { input ->
                    FlacDecoder().openPull(input).use { session ->
                        session.readInterleaved(
                            IntArray(CRC_FIXTURE_FRAMES * CRC_FIXTURE_CHANNELS),
                            CRC_FIXTURE_FRAMES
                        )
                    }
                }
            }

            assertFrameCrcMismatch("SeekableByteChannel pull decode") {
                Files.newByteChannel(path).use { input ->
                    FlacDecoder().openPull(input).use { session ->
                        session.readInterleaved(
                            IntArray(CRC_FIXTURE_FRAMES * CRC_FIXTURE_CHANNELS),
                            CRC_FIXTURE_FRAMES
                        )
                    }
                }
            }
        }
    }

    private fun withCorruptedFrameFixture(block: (Path, ByteArray) -> Unit) {
        val path = Files.createTempFile("jflac-frame-crc-corruption", ".flac")
        val format = FlacAudioFormat(
            sampleRate = 44_100,
            channels = CRC_FIXTURE_CHANNELS,
            bitsPerSample = 16,
            totalSamplesEstimate = CRC_FIXTURE_FRAMES.toLong()
        )

        try {
            /*
             * Two identical fixed-size blocks produce two equal-size constant
             * audio frames. Keeping the first frame valid is important for
             * reusable decoders: seek-to-zero can consume that frame while
             * finding its target, then normal processing observes the corrupt
             * second frame and emits the public error callback.
             */
            FlacEncoder().encode(
                output = path,
                format = format,
                samples = IntArray(CRC_FIXTURE_FRAMES * CRC_FIXTURE_CHANNELS),
                options = FlacEncodingOptions(blockSize = CRC_FIXTURE_BLOCK_FRAMES)
            )

            val corruptedBytes = corruptFinalAudioFrameCrc(Files.readAllBytes(path))
            Files.write(path, corruptedBytes)
            block(path, corruptedBytes)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun assertFrameCrcMismatch(source: String, action: () -> Unit) {
        val failure = assertFailsWith<FlacDecodeException>(source) {
            action()
        }

        assertTrue(
            failure.message.orEmpty().contains("FRAME_CRC_MISMATCH"),
            "$source did not preserve the libFLAC FRAME_CRC_MISMATCH status: ${failure.message}"
        )
    }

    private fun corruptFinalAudioFrameCrc(encodedBytes: ByteArray): ByteArray {
        val bytes = encodedBytes.copyOf()
        val firstFrameStart = findFirstAudioFrameOffset(bytes)
        val audioLength = bytes.size - firstFrameStart

        check(audioLength > FLAC_FRAME_CRC_LENGTH * 2 && audioLength % 2 == 0) {
            "Encoded fixture does not contain two equal-size FLAC audio frames."
        }

        val secondFrameStart = firstFrameStart + (audioLength / 2)
        val firstCrcOffset = secondFrameStart - FLAC_FRAME_CRC_LENGTH
        val secondCrcOffset = bytes.size - FLAC_FRAME_CRC_LENGTH

        checkFrameSync(bytes, firstFrameStart, "first")
        checkFrameSync(bytes, secondFrameStart, "second")
        checkFrameCrc(bytes, firstFrameStart, firstCrcOffset, "first")
        checkFrameCrc(bytes, secondFrameStart, secondCrcOffset, "second")

        /* Change only the second frame's stored CRC16; its compressed PCM and STREAMINFO remain untouched. */
        bytes[secondCrcOffset + 1] = (bytes[secondCrcOffset + 1].toInt() xor 0x01).toByte()
        val corruptedStoredCrc = readBigEndianCrc16(bytes, secondCrcOffset)
        val calculatedCrc = calculateFlacFrameCrc16(bytes, secondFrameStart, secondCrcOffset)

        check(corruptedStoredCrc != calculatedCrc) {
            "Frame CRC16 corruption did not invalidate the stored footer."
        }

        return bytes
    }

    private fun checkFrameSync(bytes: ByteArray, frameStart: Int, description: String) {
        /* A native FLAC frame starts with the 14-bit 0x3FFE sync code. */
        check(
            frameStart + 1 < bytes.size &&
                (bytes[frameStart].toInt() and 0xFF) == 0xFF &&
                (bytes[frameStart + 1].toInt() and 0xFE) == 0xF8
        ) {
            "Encoded fixture $description audio frame does not start with a FLAC sync code."
        }
    }

    private fun checkFrameCrc(bytes: ByteArray, frameStart: Int, crcOffset: Int, description: String) {
        val storedCrc = readBigEndianCrc16(bytes, crcOffset)
        val calculatedCrc = calculateFlacFrameCrc16(bytes, frameStart, crcOffset)

        check(storedCrc == calculatedCrc) {
            "Encoded fixture $description audio frame does not have a valid CRC16."
        }
    }

    private fun readBigEndianCrc16(bytes: ByteArray, crcOffset: Int): Int {
        return ((bytes[crcOffset].toInt() and 0xFF) shl Byte.SIZE_BITS) or
            (bytes[crcOffset + 1].toInt() and 0xFF)
    }

    private fun findFirstAudioFrameOffset(bytes: ByteArray): Int {
        check(
            bytes.size >= FLAC_MARKER_LENGTH + FLAC_METADATA_HEADER_LENGTH &&
                bytes[0] == 'f'.code.toByte() &&
                bytes[1] == 'L'.code.toByte() &&
                bytes[2] == 'a'.code.toByte() &&
                bytes[3] == 'C'.code.toByte()
        ) {
            "Encoder did not produce a native FLAC stream."
        }

        var offset = FLAC_MARKER_LENGTH
        while (true) {
            check(offset + FLAC_METADATA_HEADER_LENGTH <= bytes.size) {
                "FLAC metadata header extends past the fixture."
            }

            val isLastMetadataBlock = (bytes[offset].toInt() and 0x80) != 0
            val blockLength =
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 3].toInt() and 0xFF)
            offset += FLAC_METADATA_HEADER_LENGTH + blockLength

            check(offset <= bytes.size) {
                "FLAC metadata body extends past the fixture."
            }

            if (isLastMetadataBlock) {
                return offset
            }
        }
    }

    private fun calculateFlacFrameCrc16(bytes: ByteArray, start: Int, endExclusive: Int): Int {
        var crc = 0
        for (index in start until endExclusive) {
            crc = crc xor ((bytes[index].toInt() and 0xFF) shl Byte.SIZE_BITS)

            repeat(Byte.SIZE_BITS) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor FLAC_FRAME_CRC16_POLYNOMIAL) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }

        return crc
    }
}
