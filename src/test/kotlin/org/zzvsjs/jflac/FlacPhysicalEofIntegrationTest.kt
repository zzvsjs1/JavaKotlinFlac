package org.zzvsjs.jflac

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Complete FLAC audio frames can still form an incomplete declared stream. */
class FlacPhysicalEofIntegrationTest {
    @Test
    fun rawStreamRejectsPhysicalEofBeforeDeclaredTotal() = withFixture { _, bytes ->
        assertIncomplete { consumer -> FlacDecoder().decode(ByteArrayInputStream(bytes), consumer) }
    }

    @Test
    fun rawFileRejectsPhysicalEofBeforeDeclaredTotal() = withFixture { path, _ ->
        assertIncomplete { consumer -> FlacDecoder().decode(path, consumer) }
    }

    @Test
    fun rawChannelRejectsPhysicalEofBeforeDeclaredTotal() = withFixture { path, _ ->
        Files.newByteChannel(path).use { channel ->
            assertIncomplete { consumer -> FlacDecoder().decode(channel, consumer) }
            assertTrue(channel.isOpen)
        }
    }

    @Test
    fun pullStreamRejectsPhysicalEofBeforeDeclaredTotal() = withFixture { _, bytes ->
        FlacDecoder().openPull(ByteArrayInputStream(bytes)).use(::assertIncompletePull)
    }

    @Test
    fun pullFileRejectsPhysicalEofBeforeDeclaredTotal() = withFixture { path, _ ->
        FlacDecoder().openPull(path).use(::assertIncompletePull)
    }

    @Test
    fun pullChannelRejectsPhysicalEofBeforeDeclaredTotal() = withFixture { path, _ ->
        Files.newByteChannel(path).use { channel ->
            FlacDecoder().openPull(channel).use(::assertIncompletePull)
            assertTrue(channel.isOpen)
        }
    }

    @Test
    fun reusableSuffixRejectsPhysicalEofBeforeDeclaredTotal() = withFixture { path, _ ->
        var completed = false
        FlacDecoder().open(path).use { session ->
            assertFailsWith<FlacDecodeException> {
                session.decodeInterleaved(
                    firstSample = 4_096,
                    onChunk = FlacInterleavedPcmHandler { },
                    onComplete = FlacDecodeCompleteHandler { completed = true }
                )
            }
        }
        assertFalse(completed)
    }

    @Test
    fun oversizedRangeRejectsPhysicalEofBeforeDeclaredTotal() = withFixture { path, _ ->
        assertIncomplete { consumer -> FlacDecoder().decode(path, 0, 20_000, consumer) }
    }

    @Test
    fun boundedRangeCanCompleteBeforePhysicalEof() = withFixture { path, _ ->
        val consumer = CountingConsumer()
        FlacDecoder().decode(path, 0, 4_097, consumer)
        assertEquals(4_097, consumer.frames)
        assertTrue(consumer.completed)
    }

    @Test
    fun validSeekedSuffixAndReusableRangesRetainAbsolutePosition() = withFixture(truncate = false) { path, _ ->
        val suffix = CountingConsumer()
        FlacDecoder().decode(path, 4_097, suffix)
        assertEquals(12_287, suffix.frames)
        assertTrue(suffix.completed)

        FlacDecoder().open(path).use { session ->
            for (start in listOf(8_193L, 4_097L, 0L)) {
                val summary = session.decodeInterleaved(start, FlacInterleavedPcmHandler { })
                assertEquals(16_384L - start, summary.totalFrames)
            }
        }
    }

    @Test
    fun unknownTotalStillAcceptsPhysicalEofForRawAndPull() {
        val output = ByteArrayOutputStream()
        FlacEncoder().encode(output, FlacAudioFormat(8_000, 1, 16), samples())
        val consumer = CountingConsumer()
        FlacDecoder().decode(ByteArrayInputStream(output.toByteArray()), consumer)
        assertEquals(16_384, consumer.frames)
        assertTrue(consumer.completed)

        FlacDecoder().openPull(ByteArrayInputStream(output.toByteArray())).use { session ->
            assertEquals(0L, session.streamInfo.totalSamples)
            val chunk = IntArray(997)
            var frames = 0
            while (true) {
                val count = session.readInterleaved(chunk, chunk.size)
                if (count == -1) break
                frames += count
            }
            assertEquals(16_384, frames)
        }
    }

    @Test
    fun pcmCallbackFailureTakesPrecedenceOverIncompleteTotal() = withFixture { _, bytes ->
        val expected = IllegalStateException("PCM callback failed")
        val consumer = object : CountingConsumer() {
            override fun onPcmInterleaved(samples: IntArray, frames: Int) = throw expected
        }
        assertSame(expected, assertFailsWith<IllegalStateException> {
            FlacDecoder().decode(ByteArrayInputStream(bytes), consumer)
        })
        assertFalse(consumer.completed)
    }

    private fun assertIncomplete(decode: (PcmConsumer) -> Unit) {
        val consumer = CountingConsumer()
        assertFailsWith<FlacDecodeException> { decode(consumer) }
        assertEquals(12_288, consumer.frames)
        assertFalse(consumer.completed)
    }

    private fun assertIncompletePull(session: FlacPullDecodingSession) {
        assertEquals(16_384L, session.streamInfo.totalSamples)
        val chunk = IntArray(4_096)
        repeat(3) { assertEquals(4_096, session.readInterleaved(chunk, chunk.size)) }
        assertFailsWith<FlacDecodeException> { session.readInterleaved(chunk, chunk.size) }
    }

    private fun samples() = IntArray(16_384) { it % 1_000 - 500 }

    private fun withFixture(truncate: Boolean = true, action: (Path, ByteArray) -> Unit) {
        val output = FrameBoundaryOutput()
        FlacEncoder().encode(
            output, FlacAudioFormat(8_000, 1, 16, 16_384), samples(),
            options = FlacEncodingOptions(blockSize = 4_096)
        )
        val full = output.toByteArray()
        assertContentEquals(samples(), FlacDecoder().decode(ByteArrayInputStream(full)).interleavedSamples)

        // Native encoder writes each complete audio frame separately. Drop exactly the fourth frame,
        // preserving metadata and all three preceding CRC-protected frames without byte scanning.
        val audioStarts = output.starts.filter { offset ->
            offset + 1 < full.size && (full[offset].toInt() and 0xff) == 0xff &&
                (full[offset + 1].toInt() and 0xfe) == 0xf8
        }
        check(audioStarts.size == 4) { "Fixture must contain four separately written FLAC audio frames." }
        val bytes = if (truncate) full.copyOf(audioStarts.last()) else full
        val path = Files.createTempFile("jflac-physical-eof", ".flac")
        try {
            Files.write(path, bytes)
            action(path, bytes)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private open class CountingConsumer : PcmConsumer {
        var frames = 0
        var completed = false
        override fun onStreamInfo(streamInfo: FlacStreamInfo) = Unit
        override fun onPcmInterleaved(samples: IntArray, frames: Int) { this.frames += frames }
        override fun onComplete() { completed = true }
    }

    private class FrameBoundaryOutput : ByteArrayOutputStream() {
        val starts = mutableListOf<Int>()
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            starts += size()
            super.write(bytes, offset, length)
        }
    }
}
