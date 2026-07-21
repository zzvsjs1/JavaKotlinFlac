package org.zzvsjs.jflac

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val OGG_PAGE_HEADER_LENGTH = 27
private const val OGG_PAGE_SEGMENT_COUNT_OFFSET = 26
private const val OGG_PAGE_CHECKSUM_OFFSET = 22
private const val OGG_CRC_POLYNOMIAL = 0x04C11DB7
private const val FLAC_STREAMINFO_MD5_OFFSET_FROM_MARKER = 26

/** End-to-end coverage for the deliberately whole-stream chained Ogg API. */
class FlacChainedOggIntegrationTest {
    @Test
    fun chainedDecodeCombinesEveryLinkAcrossWholeStreamSources() {
        val fixture = createChainedFixture()
        val path = writeTemporaryOgg(fixture.bytes, "jflac-chained-decode")

        try {
            /*
             * Chaining is opt-in. A normal Ogg decoder selects the first serial
             * number and stops at its end-of-stream marker, even when another
             * complete logical bitstream follows in the same physical file.
             */
            val firstLinkOnly = FlacDecoder().decode(path)
            assertContentEquals(fixture.firstSamples, firstLinkOnly.interleavedSamples)

            val decoder = FlacDecoder(FlacDecodingOptions(decodeChainedOgg = true))
            val fromPath = decoder.decode(path)
            val fromStream = ByteArrayInputStream(fixture.bytes).use(decoder::decode)
            val fromChannel = Files.newByteChannel(path).use(decoder::decode)
            val checked = FlacDecoder(
                FlacDecodingOptions(checkMd5 = true, decodeChainedOgg = true)
            ).decode(path)
            val expected = fixture.firstSamples + fixture.secondSamples

            assertContentEquals(expected, fromPath.interleavedSamples)
            assertContentEquals(expected, fromStream.interleavedSamples)
            assertContentEquals(expected, fromChannel.interleavedSamples)
            assertContentEquals(expected, checked.interleavedSamples)
            assertEquals(expected.size.toLong() / fixture.format.channels, fromPath.totalFrames)

            /*
             * The existing public model exposes one STREAMINFO object. Values
             * that are link-specific therefore remain explicitly unknown for
             * a chain instead of incorrectly describing only its first link.
             */
            with(fromPath.streamInfo) {
                assertEquals(fixture.format.sampleRate, sampleRate)
                assertEquals(fixture.format.channels, channels)
                assertEquals(fixture.format.bitsPerSample, bitsPerSample)
                assertEquals(0L, totalSamples)
                assertEquals(0, minBlockSize)
                assertEquals(0, maxBlockSize)
                assertEquals(0, minFrameSize)
                assertEquals(0, maxFrameSize)
                assertContentEquals(ByteArray(16), md5Signature)
            }
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun chainedDecodeRejectsLinksWithDifferentPcmShapes() {
        val firstFormat = FlacAudioFormat(8_000, 1, 16, 31)
        val secondFormat = FlacAudioFormat(16_000, 1, 16, 29)
        val fixture = createChainedFixture(firstFormat, secondFormat)

        val failure = assertFailsWith<FlacDecodeException> {
            FlacDecoder(FlacDecodingOptions(decodeChainedOgg = true))
                .decode(ByteArrayInputStream(fixture.bytes))
        }

        assertTrue(failure.message.orEmpty().contains("same sample rate"))
    }

    @Test
    fun chainedDecodeOptionRejectsNativeAndPartialDecodeShapes() {
        val fixture = createChainedFixture()
        val path = writeTemporaryOgg(fixture.bytes, "jflac-chained-shape")
        val nativePath = createDecodeFixture("jflac-chained-native")
        val decoder = FlacDecoder(FlacDecodingOptions(decodeChainedOgg = true))

        try {
            val nativeFailure = assertFailsWith<IllegalArgumentException> { decoder.decode(nativePath) }
            assertTrue(nativeFailure.message.orEmpty().contains("Ogg FLAC"))

            val rangeFailure = assertFailsWith<IllegalArgumentException> {
                decoder.decode(path, firstSample = 0, maxFrames = 1)
            }

            assertTrue(rangeFailure.message.orEmpty().contains("whole-stream"))

            val sessionFailure = assertFailsWith<IllegalArgumentException> { decoder.open(path) }
            assertTrue(sessionFailure.message.orEmpty().contains("whole-stream"))

            val pullFailure = assertFailsWith<IllegalArgumentException> {
                decoder.openPull(ByteArrayInputStream(fixture.bytes))
            }

            assertTrue(pullFailure.message.orEmpty().contains("whole-stream"))
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(nativePath)
        }
    }

    @Test
    fun checkedChainedDecodeReportsIntermediateLinkMd5Failure() {
        val fixture = createChainedFixture()
        val firstLength = fixture.firstLinkLength
        val corruptedFirstLink = fixture.bytes.copyOfRange(0, firstLength)
        corruptOggStreamInfoMd5(corruptedFirstLink)
        val corruptedChain = corruptedFirstLink + fixture.bytes.copyOfRange(firstLength, fixture.bytes.size)

        /* The compressed frames remain valid when only STREAMINFO is changed. */
        val unchecked = FlacDecoder(FlacDecodingOptions(decodeChainedOgg = true))
            .decode(ByteArrayInputStream(corruptedChain))
        assertEquals(fixture.firstFrames + fixture.secondFrames, unchecked.totalFrames)

        val failure = assertFailsWith<FlacDecodeException> {
            FlacDecoder(FlacDecodingOptions(checkMd5 = true, decodeChainedOgg = true))
                .decode(ByteArrayInputStream(corruptedChain))
        }

        assertTrue(failure.message.orEmpty().contains("MD5"))
        assertTrue(failure.message.orEmpty().contains("non-final chained Ogg link"))
    }

    @Test
    fun checkedChainedDecodeReportsFinalLinkMd5Failure() {
        val fixture = createChainedFixture()
        val firstLink = fixture.bytes.copyOfRange(0, fixture.firstLinkLength)
        val corruptedFinalLink = fixture.bytes.copyOfRange(fixture.firstLinkLength, fixture.bytes.size)
        corruptOggStreamInfoMd5(corruptedFinalLink)

        val failure = assertFailsWith<FlacDecodeException> {
            FlacDecoder(FlacDecodingOptions(checkMd5 = true, decodeChainedOgg = true))
                .decode(ByteArrayInputStream(firstLink + corruptedFinalLink))
        }

        assertTrue(failure.message.orEmpty().contains("MD5"))
        assertTrue(!failure.message.orEmpty().contains("non-final"))
    }

    @Test
    fun chainedDecodeRejectsLinkEndingBeforeDeclaredTotalSamples() {
        val fixture = createChainedFixture()
        val firstLink = fixture.bytes.copyOfRange(0, fixture.firstLinkLength)
        rewriteOggTotalSamples(firstLink, fixture.firstFrames + 7)
        val secondLink = fixture.bytes.copyOfRange(fixture.firstLinkLength, fixture.bytes.size)

        val failure = assertFailsWith<FlacDecodeException> {
            FlacDecoder(FlacDecodingOptions(decodeChainedOgg = true))
                .decode(ByteArrayInputStream(firstLink + secondLink))
        }

        assertTrue(failure.message.orEmpty().contains("STREAMINFO total"))
    }

    @Test
    fun chainedDecodeRejectsLinkExceedingDeclaredTotalSamples() {
        val fixture = createChainedFixture()
        val firstLink = fixture.bytes.copyOfRange(0, fixture.firstLinkLength)
        rewriteOggTotalSamples(firstLink, fixture.firstFrames - 7)
        val secondLink = fixture.bytes.copyOfRange(fixture.firstLinkLength, fixture.bytes.size)

        val failure = assertFailsWith<FlacDecodeException> {
            FlacDecoder(FlacDecodingOptions(decodeChainedOgg = true))
                .decode(ByteArrayInputStream(firstLink + secondLink))
        }

        assertTrue(failure.message.orEmpty().contains("STREAMINFO total"))
    }

    private fun createChainedFixture(
        firstFormat: FlacAudioFormat = FlacAudioFormat(8_000, 1, 16, 31),
        secondFormat: FlacAudioFormat = FlacAudioFormat(8_000, 1, 16, 29)
    ): ChainedFixture {
        val firstFrames = requireNotNull(firstFormat.totalSamplesEstimate).toInt()
        val secondFrames = requireNotNull(secondFormat.totalSamplesEstimate).toInt()
        val firstSamples = deterministicFixturePcm(firstFrames, firstFormat)
        val secondSamples = deterministicFixturePcm(secondFrames, secondFormat, frameOffset = firstFrames)
        val firstLink = encodeOggLink(firstFormat, firstSamples, serialNumber = 1_001)
        val secondLink = encodeOggLink(secondFormat, secondSamples, serialNumber = 1_002)

        return ChainedFixture(
            bytes = firstLink + secondLink,
            firstLinkLength = firstLink.size,
            format = firstFormat,
            firstSamples = firstSamples,
            secondSamples = secondSamples,
            firstFrames = firstFrames.toLong(),
            secondFrames = secondFrames.toLong()
        )
    }

    private fun encodeOggLink(
        format: FlacAudioFormat,
        samples: IntArray,
        serialNumber: Int
    ): ByteArray {
        val output = Files.createTempFile("jflac-chained-link", ".oga")
        return try {
            FlacEncoder().encode(
                output = output,
                format = format,
                samples = samples,
                options = FlacEncodingOptions(
                    container = FlacEncodingContainer.OGG,
                    oggSerialNumber = serialNumber
                )
            )
            val bytes = Files.readAllBytes(output)
            val markerIndex = findFlacMarker(bytes)
            val md5Start = markerIndex + FLAC_STREAMINFO_MD5_OFFSET_FROM_MARKER
            check(bytes.copyOfRange(md5Start, md5Start + 16).any { value -> value != 0.toByte() }) {
                "Seekable Ogg fixture did not back-patch a STREAMINFO MD5 signature."
            }

            bytes
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private fun writeTemporaryOgg(bytes: ByteArray, prefix: String): Path {
        val path = Files.createTempFile(prefix, ".oga")
        Files.write(path, bytes)
        return path
    }

    private fun corruptOggStreamInfoMd5(bytes: ByteArray) {
        val markerIndex = findFlacMarker(bytes)
        val md5Index = markerIndex + FLAC_STREAMINFO_MD5_OFFSET_FROM_MARKER
        require(md5Index < bytes.size) { "The Ogg FLAC STREAMINFO block is truncated." }
        bytes[md5Index] = (bytes[md5Index].toInt() xor 0x01).toByte()
        rewriteContainingOggPageChecksum(bytes, md5Index)
    }

    private fun rewriteOggTotalSamples(bytes: ByteArray, totalSamples: Long) {
        require(totalSamples in 0 until (1L shl 36)) { "FLAC total samples must fit its 36-bit field." }

        val markerIndex = findFlacMarker(bytes)
        val highTotalByte = markerIndex + 21
        bytes[highTotalByte] = (
            (bytes[highTotalByte].toInt() and 0xF0) or
                ((totalSamples ushr 32).toInt() and 0x0F)
            ).toByte()
        repeat(4) { index ->
            bytes[markerIndex + 22 + index] = (totalSamples ushr ((3 - index) * Byte.SIZE_BITS)).toByte()
        }

        rewriteContainingOggPageChecksum(bytes, highTotalByte)
    }

    private fun findFlacMarker(bytes: ByteArray): Int {
        val markerIndex = bytes.indexOfSequence(
            byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())
        )
        require(markerIndex >= 0) { "The Ogg FLAC link does not contain a native FLAC marker." }
        return markerIndex
    }

    private fun rewriteContainingOggPageChecksum(bytes: ByteArray, changedIndex: Int) {
        var pageStart = 0
        while (pageStart < bytes.size) {
            require(pageStart + OGG_PAGE_HEADER_LENGTH <= bytes.size) { "The Ogg page header is truncated." }

            require(
                bytes[pageStart] == 'O'.code.toByte() &&
                    bytes[pageStart + 1] == 'g'.code.toByte() &&
                    bytes[pageStart + 2] == 'g'.code.toByte() &&
                    bytes[pageStart + 3] == 'S'.code.toByte()
            ) { "The Ogg capture pattern is invalid." }

            val segmentCount = bytes[pageStart + OGG_PAGE_SEGMENT_COUNT_OFFSET].toInt() and 0xFF
            val lacingStart = pageStart + OGG_PAGE_HEADER_LENGTH
            require(lacingStart + segmentCount <= bytes.size) { "The Ogg lacing table is truncated." }

            var bodyLength = 0
            repeat(segmentCount) { index -> bodyLength += bytes[lacingStart + index].toInt() and 0xFF }
            val pageEnd = lacingStart + segmentCount + bodyLength
            require(pageEnd <= bytes.size) { "The Ogg page body is truncated." }

            if (changedIndex in pageStart until pageEnd) {
                repeat(Int.SIZE_BYTES) { index -> bytes[pageStart + OGG_PAGE_CHECKSUM_OFFSET + index] = 0 }
                val checksum = calculateOggChecksum(bytes, pageStart, pageEnd)
                repeat(Int.SIZE_BYTES) { index ->
                    bytes[pageStart + OGG_PAGE_CHECKSUM_OFFSET + index] =
                        (checksum ushr (index * Byte.SIZE_BITS)).toByte()
                }

                return
            }

            pageStart = pageEnd
        }

        error("The changed STREAMINFO byte does not belong to an Ogg page.")
    }

    private fun calculateOggChecksum(bytes: ByteArray, pageStart: Int, pageEnd: Int): Int {
        var checksum = 0
        for (index in pageStart until pageEnd) {
            checksum = checksum xor ((bytes[index].toInt() and 0xFF) shl 24)
            repeat(Byte.SIZE_BITS) {
                checksum = if ((checksum and Int.MIN_VALUE) != 0) {
                    (checksum shl 1) xor OGG_CRC_POLYNOMIAL
                } else {
                    checksum shl 1
                }
            }
        }

        return checksum
    }

    private fun ByteArray.indexOfSequence(sequence: ByteArray): Int {
        if (sequence.isEmpty()) {
            return 0
        }

        for (start in 0..size - sequence.size) {
            if (sequence.indices.all { offset -> this[start + offset] == sequence[offset] }) {
                return start
            }
        }

        return -1
    }

    private data class ChainedFixture(
        val bytes: ByteArray,
        val firstLinkLength: Int,
        val format: FlacAudioFormat,
        val firstSamples: IntArray,
        val secondSamples: IntArray,
        val firstFrames: Long,
        val secondFrames: Long
    )
}
