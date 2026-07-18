package org.zzvsjs.jflac.internal

import org.zzvsjs.jflac.*
import org.junit.AfterClass
import org.junit.BeforeClass
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val FLAC_METADATA_LAST_BLOCK_FLAG = 0x80
private const val FLAC_METADATA_TYPE_STREAMINFO = 0
private const val FLAC_METADATA_TYPE_PADDING = 1
private const val FLAC_METADATA_TYPE_APPLICATION = 2
private const val FLAC_METADATA_TYPE_VORBIS_COMMENT = 4
private const val FLAC_METADATA_TYPE_UNKNOWN_FIXTURE = 42
private const val FLAC_STREAMINFO_LENGTH = 34
private const val FLAC_STREAMINFO_MD5_OFFSET = 26
private const val FLAC_METADATA_MAX_PAYLOAD_LENGTH = 0xFF_FFFF

/**
 * End-to-end tests for the file-based wrapper.
 *
 * The goal is to cover the public API behaviour, not libFLAC internals. The
 * generated sample file is treated as the integration fixture.
 */
class FlacIntegrationTest {
    private val sampleFile: Path
        get() = sharedSampleFile

    companion object {
        private lateinit var sharedSampleFile: Path

        @JvmStatic
        @BeforeClass
        fun createSampleFixture() {
            sharedSampleFile = createDecodeFixture("jflac-music-fixture")
        }

        @JvmStatic
        @AfterClass
        fun deleteSampleFixture() {
            if (::sharedSampleFile.isInitialized) {
                Files.deleteIfExists(sharedSampleFile)
            }
        }
    }

    @Test
    fun metadataReaderReturnsExpectedStructure() {
        // A real file should produce a populated metadata object with the core
        // STREAMINFO fields filled in by libFLAC.
        val metadata = FlacMetadataReader().read(sampleFile)

        assertTrue(metadata.streamInfo.sampleRate > 0)
        assertTrue(metadata.streamInfo.channels > 0)
        assertTrue(metadata.streamInfo.bitsPerSample > 0)
        assertTrue(metadata.streamInfo.totalSamples > 0)
        assertEquals(16, metadata.streamInfo.md5Signature.size)
        assertNotNull(metadata.pictures)
    }

    @Test
    fun metadataReaderPreservesPhysicalOrderFromFixtureBytes() {
        val fixture = Files.createTempFile("jflac-ordered-read-fixture", ".flac")
        try {
            Files.write(fixture, metadataOnlyFlacFixture())

            val blocks = FlacMetadataReader().read(fixture).blocks

            assertEquals(4, blocks.size)
            assertIs<FlacMetadataBlock.Padding>(blocks[0])
            assertEquals(3, (blocks[0] as FlacMetadataBlock.Padding).padding.length)

            assertIs<FlacMetadataBlock.Application>(blocks[1])
            val application = (blocks[1] as FlacMetadataBlock.Application).application
            assertContentEquals(
                byteArrayOf('T'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte()),
                application.id
            )
            assertContentEquals(byteArrayOf(0x10, 0x20), application.data)

            assertIs<FlacMetadataBlock.Unknown>(blocks[2])
            val unknown = (blocks[2] as FlacMetadataBlock.Unknown).unknown
            assertEquals(FLAC_METADATA_TYPE_UNKNOWN_FIXTURE, unknown.type)
            assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), unknown.data)

            assertIs<FlacMetadataBlock.VorbisComment>(blocks[3])
            val comment = (blocks[3] as FlacMetadataBlock.VorbisComment).comment
            assertEquals("fixture", comment.vendor)
            assertEquals(listOf("Fixture"), comment.comments["TITLE"])
        } finally {
            Files.deleteIfExists(fixture)
        }
    }

    @Test
    fun decoderProducesConsistentInterleavedFrames() {
        val consumer = CollectingConsumer()
        FlacDecoder().decode(sampleFile, consumer)

        // The decoder reports total sample frames, while the callback receives
        // chunks. The accumulator checks that chunk shapes stay consistent.
        assertNotNull(consumer.streamInfo)
        assertTrue(consumer.completed)
        assertTrue(consumer.totalFrames > 0)
        assertEquals(0L, consumer.invalidChunkCount)
        assertEquals(consumer.streamInfo!!.totalSamples, consumer.totalFrames)
    }

    @Test
    fun md5CheckingAcceptsValidWholeStreamSources() {
        val decoder = FlacDecoder(FlacDecodingOptions(checkMd5 = true))
        val fromFile = decoder.decode(sampleFile)

        val fromStream = Files.newInputStream(sampleFile).use { input ->
            decoder.decode(input)
        }
        val fromChannel = Files.newByteChannel(sampleFile).use { channel ->
            decoder.decode(channel)
        }

        assertContentEquals(fromFile.interleavedSamples, fromStream.interleavedSamples)
        assertContentEquals(fromFile.interleavedSamples, fromChannel.interleavedSamples)
    }

    @Test
    fun md5CheckingRejectsMismatchedStreamInfoSignature() {
        val corrupted = Files.createTempFile("jflac-md5-mismatch", ".flac")
        try {
            val bytes = Files.readAllBytes(sampleFile)
            assertEquals(FLAC_METADATA_TYPE_STREAMINFO, bytes[4].toInt() and 0x7F)

            /*
             * STREAMINFO starts immediately after the four-byte FLAC marker
             * and its four-byte metadata header. The 16-byte MD5 signature
             * begins at absolute byte 26, so changing one signature byte keeps
             * the encoded frames valid while making the checksum disagree.
             */
            bytes[FLAC_STREAMINFO_MD5_OFFSET] =
                (bytes[FLAC_STREAMINFO_MD5_OFFSET].toInt() xor 0x01).toByte()
            Files.write(corrupted, bytes)

            val unchecked = FlacDecoder().decode(corrupted)
            assertTrue(unchecked.totalFrames > 0L)

            val decoder = FlacDecoder(FlacDecodingOptions(checkMd5 = true))
            val failures = listOf(
                assertFailsWith<FlacDecodeException> { decoder.decode(corrupted) },
                assertFailsWith<FlacDecodeException> {
                    Files.newInputStream(corrupted).use(decoder::decode)
                },
                assertFailsWith<FlacDecodeException> {
                    Files.newByteChannel(corrupted).use(decoder::decode)
                }
            )
            failures.forEach { failure -> assertTrue(failure.message.orEmpty().contains("MD5")) }
        } finally {
            Files.deleteIfExists(corrupted)
        }
    }

    @Test
    fun md5CheckingAcceptsAnUnavailableZeroSignature() {
        val withoutSignature = Files.createTempFile("jflac-md5-unavailable", ".flac")
        try {
            val bytes = Files.readAllBytes(sampleFile)
            bytes.fill(
                element = 0,
                fromIndex = FLAC_STREAMINFO_MD5_OFFSET,
                toIndex = FLAC_STREAMINFO_MD5_OFFSET + 16
            )
            Files.write(withoutSignature, bytes)

            val decoded = FlacDecoder(FlacDecodingOptions(checkMd5 = true)).decode(withoutSignature)
            assertTrue(decoded.totalFrames > 0L)
            assertContentEquals(ByteArray(16), decoded.streamInfo.md5Signature)
        } finally {
            Files.deleteIfExists(withoutSignature)
        }
    }

    @Test
    fun md5CheckingRejectsPartialAndSessionDecodeShapes() {
        val decoder = FlacDecoder(FlacDecodingOptions(checkMd5 = true))

        val rangeFailure = assertFailsWith<IllegalArgumentException> {
            decoder.decode(sampleFile, firstSample = 0, maxFrames = 1)
        }
        assertTrue(rangeFailure.message.orEmpty().contains("whole-stream"))

        val sessionFailure = assertFailsWith<IllegalArgumentException> {
            decoder.open(sampleFile)
        }
        assertTrue(sessionFailure.message.orEmpty().contains("whole-stream"))

        val pullFailure = assertFailsWith<IllegalArgumentException> {
            decoder.openPull(sampleFile)
        }
        assertTrue(pullFailure.message.orEmpty().contains("whole-stream"))

        val seekFailure = assertFailsWith<IllegalArgumentException> {
            decoder.decode(sampleFile, firstSample = 0, consumer = CollectingConsumer())
        }
        assertTrue(seekFailure.message.orEmpty().contains("whole-stream"))

        Files.newByteChannel(sampleFile).use { channel ->
            val channelSessionFailure = assertFailsWith<IllegalArgumentException> {
                decoder.open(channel)
            }
            assertTrue(channelSessionFailure.message.orEmpty().contains("whole-stream"))
        }
    }

    @Test
    fun fullDecodeMatchesStreamingDecodeSummary() {
        val decoder = FlacDecoder()
        val decoded = decoder.decode(sampleFile)
        val streamedChunks = ArrayList<FlacInterleavedPcmChunk>()

        val summary = decoder.decodeInterleaved(
            path = sampleFile,
            onChunk = FlacInterleavedPcmHandler { chunk -> streamedChunks.add(chunk) }
        )

        assertEquals(decoded.streamInfo, summary.streamInfo)
        assertEquals(decoded.totalFrames, summary.totalFrames)
        assertEquals(decoded.interleavedSamples.size.toLong(), summary.totalSamples)
        assertTrue(streamedChunks.isNotEmpty())
        assertEquals(0L, streamedChunks.first().firstFrameIndex)
        assertEquals(decoded.totalFrames, streamedChunks.sumOf { it.frames.toLong() })
    }

    @Test
    fun channelDecodeSplitsInterleavedChunksWithoutChangingFramePositions() {
        val decoder = FlacDecoder()
        val interleavedChunks = ArrayList<FlacInterleavedPcmChunk>()
        val channelChunks = ArrayList<FlacChannelPcmChunk>()

        val interleavedSummary = decoder.decodeInterleaved(
            path = sampleFile,
            onChunk = FlacInterleavedPcmHandler { chunk -> interleavedChunks.add(chunk) }
        )
        val channelSummary = decoder.decodeChannels(
            path = sampleFile,
            onChunk = FlacChannelPcmHandler { chunk -> channelChunks.add(chunk) }
        )

        assertEquals(interleavedSummary, channelSummary)
        assertEquals(interleavedChunks.size, channelChunks.size)

        interleavedChunks.zip(channelChunks).forEach { (interleaved, channels) ->
            assertEquals(interleaved.frames, channels.frames)
            assertEquals(interleaved.firstFrameIndex, channels.firstFrameIndex)
            assertEquals(interleaved.splitByChannel().map(IntArray::toList), channels.channelSamples.map(IntArray::toList))
        }
    }

    @Test
    fun decodeListenerReceivesLifecycleCallbacksInOrder() {
        val events = ArrayList<String>()

        val summary = FlacDecoder().decode(
            sampleFile,
            object : FlacDecodeAdapter() {
                override fun onStreamInfo(info: FlacStreamInfo) {
                    events.add("stream-info")
                }

                override fun onInterleavedPcm(chunk: FlacInterleavedPcmChunk) {
                    if (events.lastOrNull() != "pcm") {
                        events.add("pcm")
                    }
                }

                override fun onComplete(summary: FlacDecodeSummary) {
                    events.add("complete")
                }
            }
        )

        assertEquals(listOf("stream-info", "pcm", "complete"), events)
        assertTrue(summary.totalFrames > 0)
    }

    @Test
    fun seekedInterleavedDecodeReportsAbsoluteFramePositions() {
        val streamInfo = FlacMetadataReader().read(sampleFile).streamInfo
        val firstSample = streamInfo.totalSamples / 2
        val chunks = ArrayList<FlacInterleavedPcmChunk>()

        val summary = FlacDecoder().decodeInterleaved(
            path = sampleFile,
            firstSample = firstSample,
            onChunk = FlacInterleavedPcmHandler { chunk -> chunks.add(chunk) }
        )

        assertTrue(chunks.isNotEmpty())
        assertEquals(firstSample, chunks.first().firstFrameIndex)
        assertEquals(streamInfo.totalSamples - firstSample, summary.totalFrames)

        var expectedFirstFrame = firstSample
        chunks.forEach { chunk ->
            assertEquals(expectedFirstFrame, chunk.firstFrameIndex)
            expectedFirstFrame += chunk.frames.toLong()
        }
        assertEquals(streamInfo.totalSamples, expectedFirstFrame)
    }

    @Test
    fun seekedChannelDecodeUsesSameSummaryAsSeekedInterleavedDecode() {
        val streamInfo = FlacMetadataReader().read(sampleFile).streamInfo
        val firstSample = streamInfo.totalSamples / 3
        val interleavedChunks = ArrayList<FlacInterleavedPcmChunk>()
        val channelChunks = ArrayList<FlacChannelPcmChunk>()

        val interleavedSummary = FlacDecoder().decodeInterleaved(
            path = sampleFile,
            firstSample = firstSample,
            onChunk = FlacInterleavedPcmHandler { chunk -> interleavedChunks.add(chunk) }
        )
        val channelSummary = FlacDecoder().decodeChannels(
            path = sampleFile,
            firstSample = firstSample,
            onChunk = FlacChannelPcmHandler { chunk -> channelChunks.add(chunk) }
        )

        assertEquals(interleavedSummary, channelSummary)
        assertEquals(interleavedChunks.size, channelChunks.size)
        assertEquals(firstSample, channelChunks.first().firstFrameIndex)
    }

    @Test
    fun rangeDecodeReturnsRequestedPcmWindow() {
        val decoder = FlacDecoder()
        val full = decoder.decode(sampleFile)
        val firstSample = full.streamInfo.totalSamples / 4
        val maxFrames = 1_337L

        val range = decoder.decode(sampleFile, firstSample, maxFrames)

        assertEquals(full.streamInfo, range.streamInfo)
        assertEquals(maxFrames, range.totalFrames)
        assertSamplesMatchFullAudio(full, firstSample, range)
    }

    @Test
    fun rangeInterleavedDecodeStopsAtRequestedFrameCount() {
        val streamInfo = FlacMetadataReader().read(sampleFile).streamInfo
        val firstSample = streamInfo.totalSamples / 3
        val maxFrames = 2_049L
        val chunks = ArrayList<FlacInterleavedPcmChunk>()

        val summary = FlacDecoder().decodeInterleaved(
            path = sampleFile,
            firstSample = firstSample,
            maxFrames = maxFrames,
            onChunk = FlacInterleavedPcmHandler { chunk -> chunks.add(chunk) }
        )

        assertEquals(maxFrames, summary.totalFrames)
        assertTrue(chunks.isNotEmpty())
        assertEquals(firstSample, chunks.first().firstFrameIndex)
        assertEquals(firstSample + maxFrames, chunks.last().firstFrameIndex + chunks.last().frames)
    }

    @Test
    fun rangeChannelDecodeUsesSameSummaryAsRangeInterleavedDecode() {
        val streamInfo = FlacMetadataReader().read(sampleFile).streamInfo
        val firstSample = streamInfo.totalSamples / 5
        val maxFrames = 733L
        val interleavedChunks = ArrayList<FlacInterleavedPcmChunk>()
        val channelChunks = ArrayList<FlacChannelPcmChunk>()

        val interleavedSummary = FlacDecoder().decodeInterleaved(
            path = sampleFile,
            firstSample = firstSample,
            maxFrames = maxFrames,
            onChunk = FlacInterleavedPcmHandler { chunk -> interleavedChunks.add(chunk) }
        )
        val channelSummary = FlacDecoder().decodeChannels(
            path = sampleFile,
            firstSample = firstSample,
            maxFrames = maxFrames,
            onChunk = FlacChannelPcmHandler { chunk -> channelChunks.add(chunk) }
        )

        assertEquals(interleavedSummary, channelSummary)
        assertEquals(maxFrames, channelSummary.totalFrames)
        assertEquals(interleavedChunks.size, channelChunks.size)
        assertEquals(firstSample, channelChunks.first().firstFrameIndex)
    }

    @Test
    fun rangeDecodeClipsLongRequestAtEndOfStream() {
        val streamInfo = FlacMetadataReader().read(sampleFile).streamInfo
        val remainingFrames = 37L
        val firstSample = streamInfo.totalSamples - remainingFrames
        val chunks = ArrayList<FlacInterleavedPcmChunk>()

        val summary = FlacDecoder().decodeInterleaved(
            path = sampleFile,
            firstSample = firstSample,
            maxFrames = remainingFrames + 1_000L,
            onChunk = FlacInterleavedPcmHandler { chunk -> chunks.add(chunk) }
        )

        assertEquals(remainingFrames, summary.totalFrames)
        assertTrue(chunks.isNotEmpty())
        assertEquals(streamInfo.totalSamples, chunks.last().firstFrameIndex + chunks.last().frames)
    }

    @Test
    fun rangeDecodeWithZeroFramesCompletesWithoutPcmChunks() {
        val streamInfo = FlacMetadataReader().read(sampleFile).streamInfo
        val firstSample = streamInfo.totalSamples / 2
        val chunks = ArrayList<FlacInterleavedPcmChunk>()
        var completedSummary: FlacDecodeSummary? = null

        val summary = FlacDecoder().decodeInterleaved(
            path = sampleFile,
            firstSample = firstSample,
            maxFrames = 0,
            onChunk = FlacInterleavedPcmHandler { chunk -> chunks.add(chunk) },
            onComplete = FlacDecodeCompleteHandler { complete -> completedSummary = complete }
        )

        assertEquals(streamInfo, summary.streamInfo)
        assertEquals(0L, summary.totalFrames)
        assertEquals(summary, completedSummary)
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun rangeDecodeStartingAtEndReturnsEmptyResult() {
        val streamInfo = FlacMetadataReader().read(sampleFile).streamInfo
        val chunks = ArrayList<FlacInterleavedPcmChunk>()

        val summary = FlacDecoder().decodeInterleaved(
            path = sampleFile,
            firstSample = streamInfo.totalSamples,
            maxFrames = 10,
            onChunk = FlacInterleavedPcmHandler { chunk -> chunks.add(chunk) }
        )

        assertEquals(streamInfo, summary.streamInfo)
        assertEquals(0L, summary.totalFrames)
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun decodingSessionCanDecodeSeveralPcmWindows() {
        val full = FlacDecoder().decode(sampleFile)
        val firstSample = full.streamInfo.totalSamples / 6
        val secondSample = full.streamInfo.totalSamples / 2

        FlacDecoder().open(sampleFile).use { session ->
            val first = session.decodeInterleaved(
                firstSample = firstSample,
                maxFrames = 300,
                onChunk = FlacInterleavedPcmHandler { }
            )
            val secondChunks = ArrayList<FlacInterleavedPcmChunk>()
            val second = session.decodeInterleaved(
                firstSample = secondSample,
                maxFrames = 512,
                onChunk = FlacInterleavedPcmHandler { chunk -> secondChunks.add(chunk) }
            )

            assertEquals(300L, first.totalFrames)
            assertEquals(512L, second.totalFrames)
            assertTrue(secondChunks.isNotEmpty())
            assertEquals(secondSample, secondChunks.first().firstFrameIndex)
        }
    }

    @Test
    fun decodingSessionRangeCallbackFailureReleasesNativeHandle() {
        val session = FlacDecoder().open(sampleFile)

        assertFailsWith<IllegalStateException> {
            session.decodeInterleaved(
                firstSample = 0,
                maxFrames = 10,
                onChunk = FlacInterleavedPcmHandler { throw IllegalStateException("callback failed") }
            )
        }

        assertFailsWith<IllegalStateException> {
            session.decodeInterleaved(
                firstSample = 0,
                maxFrames = 10,
                onChunk = FlacInterleavedPcmHandler { }
            )
        }
    }

    @Test
    fun rangeDecodeRejectsNegativeMaxFrames() {
        assertFailsWith<IllegalArgumentException> {
            FlacDecoder().decode(sampleFile, firstSample = 0, maxFrames = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            FlacDecoder().decodeInterleaved(
                path = sampleFile,
                firstSample = 0,
                maxFrames = -1,
                onChunk = FlacInterleavedPcmHandler { }
            )
        }

        FlacDecoder().open(sampleFile).use { session ->
            assertFailsWith<IllegalArgumentException> {
                session.decodeInterleaved(
                    firstSample = 0,
                    maxFrames = -1,
                    onChunk = FlacInterleavedPcmHandler { }
                )
            }
        }
    }

    @Test
    fun rangeDecodeRejectsOverflowingFrameWindow() {
        assertFailsWith<IllegalArgumentException> {
            FlacDecoder().decodeInterleaved(
                path = sampleFile,
                firstSample = 1,
                maxFrames = Long.MAX_VALUE,
                onChunk = FlacInterleavedPcmHandler { }
            )
        }

        FlacDecoder().open(sampleFile).use { session ->
            assertFailsWith<IllegalArgumentException> {
                session.decodeInterleaved(
                    firstSample = 1,
                    maxFrames = Long.MAX_VALUE,
                    onChunk = FlacInterleavedPcmHandler { }
                )
            }
        }
    }

    @Test
    fun seekBeyondEndThrowsDecodeException() {
        val streamInfo = FlacMetadataReader().read(sampleFile).streamInfo

        assertFailsWith<FlacDecodeException> {
            FlacDecoder().decodeInterleaved(
                path = sampleFile,
                firstSample = streamInfo.totalSamples + 1,
                onChunk = FlacInterleavedPcmHandler { }
            )
        }
    }

    @Test
    fun decodingSessionCanSeekDecodeMoreThanOnce() {
        val streamInfo = FlacMetadataReader().read(sampleFile).streamInfo
        val firstSeek = streamInfo.totalSamples / 4
        val secondSeek = streamInfo.totalSamples / 2
        val firstChunks = ArrayList<FlacInterleavedPcmChunk>()
        val secondChunks = ArrayList<FlacInterleavedPcmChunk>()
        val backwardChunks = ArrayList<FlacInterleavedPcmChunk>()

        FlacDecoder().open(sampleFile).use { session ->
            val firstSummary = session.decodeInterleaved(
                firstSample = firstSeek,
                onChunk = FlacInterleavedPcmHandler { chunk -> firstChunks.add(chunk) }
            )
            val secondSummary = session.decodeInterleaved(
                firstSample = secondSeek,
                onChunk = FlacInterleavedPcmHandler { chunk -> secondChunks.add(chunk) }
            )
            val backwardSummary = session.decodeInterleaved(
                firstSample = firstSeek,
                onChunk = FlacInterleavedPcmHandler { chunk -> backwardChunks.add(chunk) }
            )

            assertEquals(streamInfo.totalSamples - firstSeek, firstSummary.totalFrames)
            assertEquals(streamInfo.totalSamples - secondSeek, secondSummary.totalFrames)
            assertEquals(firstSummary, backwardSummary)
            assertInterleavedChunksEqual(firstChunks, backwardChunks)
            assertEquals(firstSeek, firstChunks.first().firstFrameIndex)
            assertEquals(secondSeek, secondChunks.first().firstFrameIndex)
        }
    }

    @Test
    fun decodingSessionRejectsUseAfterClose() {
        val session = FlacDecoder().open(sampleFile)
        session.close()

        assertFailsWith<IllegalStateException> {
            session.decodeInterleaved(
                firstSample = 0,
                onChunk = FlacInterleavedPcmHandler { }
            )
        }
    }

    @Test
    fun decodingSessionCloseIsIdempotent() {
        val session = FlacDecoder().open(sampleFile)

        session.close()
        session.close()

        assertFailsWith<IllegalStateException> {
            session.decodeInterleaved(
                firstSample = 0,
                onChunk = FlacInterleavedPcmHandler { }
            )
        }
    }

    @Test
    fun decodingSessionRejectsConcurrentDecode() {
        FlacDecoder().open(sampleFile).use { session ->
            val callbackEntered = CountDownLatch(1)
            val releaseCallback = CountDownLatch(1)
            val workerFailure = AtomicReference<Throwable?>()
            val worker = Thread {
                try {
                    session.decodeInterleaved(
                        firstSample = 0,
                        onChunk = FlacInterleavedPcmHandler { },
                        onStreamInfo = FlacStreamInfoHandler {
                            callbackEntered.countDown()
                            assertTrue(releaseCallback.await(5, TimeUnit.SECONDS))
                        }
                    )
                } catch (t: Throwable) {
                    workerFailure.set(t)
                }
            }

            worker.start()
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
            try {
                assertFailsWith<IllegalStateException> {
                    session.decodeInterleaved(
                        firstSample = 0,
                        onChunk = FlacInterleavedPcmHandler { }
                    )
                }
            } finally {
                releaseCallback.countDown()
                worker.join(5_000)
            }

            assertTrue(!worker.isAlive)
            workerFailure.get()?.let { throw it }
        }
    }

    @Test
    fun decodingSessionCloseDuringDecodeClosesAfterCurrentDecode() {
        val session = FlacDecoder().open(sampleFile)
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                session.decodeInterleaved(
                    firstSample = 0,
                    onChunk = FlacInterleavedPcmHandler { },
                    onStreamInfo = FlacStreamInfoHandler {
                        callbackEntered.countDown()
                        assertTrue(releaseCallback.await(5, TimeUnit.SECONDS))
                    }
                )
            } catch (t: Throwable) {
                workerFailure.set(t)
            }
        }

        worker.start()
        assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
        try {
            session.close()
            assertFailsWith<IllegalStateException> {
                session.decodeInterleaved(
                    firstSample = 0,
                    onChunk = FlacInterleavedPcmHandler { }
                )
            }
        } finally {
            releaseCallback.countDown()
            worker.join(5_000)
        }

        assertTrue(!worker.isAlive)
        workerFailure.get()?.let { throw it }
        assertFailsWith<IllegalStateException> {
            session.decodeInterleaved(
                firstSample = 0,
                onChunk = FlacInterleavedPcmHandler { }
            )
        }
    }

    @Test
    fun decodingSessionReleasesNativeHandleAfterSeekFailure() {
        val streamInfo = FlacMetadataReader().read(sampleFile).streamInfo
        val session = FlacDecoder().open(sampleFile)

        assertFailsWith<FlacDecodeException> {
            session.decodeInterleaved(
                firstSample = streamInfo.totalSamples + 1,
                onChunk = FlacInterleavedPcmHandler { }
            )
        }

        assertFailsWith<IllegalStateException> {
            session.decodeInterleaved(
                firstSample = 0,
                onChunk = FlacInterleavedPcmHandler { }
            )
        }
    }

    @Test
    fun decodingSessionReleasesNativeHandleWhenStreamInfoCallbackFails() {
        val session = FlacDecoder().open(sampleFile)

        assertFailsWith<IllegalStateException> {
            session.decodeInterleaved(
                firstSample = 0,
                onChunk = { },
                onStreamInfo = { throw IllegalStateException("callback failed") }
            )
        }

        assertFailsWith<IllegalStateException> {
            session.decodeInterleaved(
                firstSample = 0,
                onChunk = FlacInterleavedPcmHandler { }
            )
        }
    }

    @Test
    fun decodingSessionReleasesNativeHandleWhenPcmCallbackFails() {
        val session = FlacDecoder().open(sampleFile)

        assertFailsWith<IllegalStateException> {
            session.decodeInterleaved(
                firstSample = 0,
                onChunk = FlacInterleavedPcmHandler { throw IllegalStateException("callback failed") }
            )
        }

        assertFailsWith<IllegalStateException> {
            session.decodeInterleaved(
                firstSample = 0,
                onChunk = FlacInterleavedPcmHandler { }
            )
        }
    }

    @Test
    fun decodingSessionReleasesNativeHandleWhenCompleteCallbackFails() {
        val session = FlacDecoder().open(sampleFile)

        assertFailsWith<IllegalStateException> {
            session.decodeInterleaved(
                firstSample = 0,
                onChunk = FlacInterleavedPcmHandler { },
                onComplete = FlacDecodeCompleteHandler { throw IllegalStateException("callback failed") }
            )
        }

        assertFailsWith<IllegalStateException> {
            session.decodeInterleaved(
                firstSample = 0,
                onChunk = FlacInterleavedPcmHandler { }
            )
        }
    }

    @Test
    fun nativeSessionDecodeRejectsInvalidHandle() {
        FlacNativeLoader.load()

        assertFailsWith<IllegalStateException> {
            NativeBindings.decodeDecoderFrom(0, 0, CollectingConsumer())
        }
        assertFailsWith<IllegalStateException> {
            NativeBindings.decodeDecoderRange(0, 0, 1, CollectingConsumer())
        }
        assertFailsWith<IllegalStateException> {
            NativeBindings.decodeDecoderFrom(Long.MAX_VALUE, 0, CollectingConsumer())
        }
        assertFailsWith<IllegalStateException> {
            NativeBindings.decodeDecoderRange(Long.MAX_VALUE, 0, 1, CollectingConsumer())
        }

        val handle = NativeBindings.openDecoderFile(sampleFile.toString(), NativeFlacContainer.NATIVE.nativeCode)
        NativeBindings.releaseDecoder(handle)
        NativeBindings.releaseDecoder(handle)
        assertFailsWith<IllegalStateException> {
            NativeBindings.decodeDecoderFrom(handle, 0, CollectingConsumer())
        }
        assertFailsWith<IllegalStateException> {
            NativeBindings.decodeDecoderRange(handle, 0, 1, CollectingConsumer())
        }
    }

    @Test
    fun metadataReaderRejectsMultipleVorbisCommentBlocks() {
        val fixture = Files.createTempFile("jflac-duplicate-vorbis-fixture", ".flac")
        try {
            Files.write(fixture, duplicateVorbisCommentFixture())

            val error = assertFailsWith<FlacDecodeException> {
                FlacMetadataReader().read(fixture)
            }
            assertTrue(error.message.orEmpty().contains("multiple VORBIS_COMMENT"))
        } finally {
            Files.deleteIfExists(fixture)
        }
    }

    @Test
    fun nativeEncoderReturnsRegistryHandle() {
        FlacNativeLoader.load()
        val output = Files.createTempFile("jflac-native-encoder-handle", ".flac")
        val request = NativeEncodingRequest(
            44_100,
            1,
            16,
            1L,
            5,
            true,
            true,
            null,
            NativeFlacContainer.NATIVE.nativeCode,
            null,
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            intArrayOf(),
            emptyArray(),
            1
        )
        val handle = NativeBindings.openEncoderFile(output.toString(), request)
        var finished = false

        try {
            assertTrue(handle in 1L..Int.MAX_VALUE.toLong())
            NativeBindings.writeEncoderInterleaved(handle, intArrayOf(0), 1)
            NativeBindings.finishEncoder(handle)
            finished = true
        } finally {
            if (!finished) {
                NativeBindings.releaseEncoder(handle)
            }
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun nativeEncoderRejectsInvalidAndStaleHandles() {
        FlacNativeLoader.load()
        val output = Files.createTempFile("jflac-native-encoder-stale-handle", ".flac")
        val request = NativeEncodingRequest(
            44_100,
            1,
            16,
            1L,
            5,
            true,
            true,
            null,
            NativeFlacContainer.NATIVE.nativeCode,
            null,
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            emptyArray(),
            intArrayOf(),
            emptyArray(),
            1
        )

        try {
            assertFailsWith<IllegalStateException> {
                NativeBindings.writeEncoderInterleaved(Long.MAX_VALUE, intArrayOf(0), 1)
            }
            assertFailsWith<IllegalStateException> {
                NativeBindings.finishEncoder(Long.MAX_VALUE)
            }
            NativeBindings.releaseEncoder(Long.MAX_VALUE)

            val handle = NativeBindings.openEncoderFile(output.toString(), request)
            NativeBindings.releaseEncoder(handle)
            assertFailsWith<IllegalStateException> {
                NativeBindings.writeEncoderInterleaved(handle, intArrayOf(0), 1)
            }
            assertFailsWith<IllegalStateException> {
                NativeBindings.finishEncoder(handle)
            }
            NativeBindings.releaseEncoder(handle)
        } finally {
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun nativeSessionRangeDecodeRejectsInvalidArguments() {
        FlacNativeLoader.load()
        val handle = NativeBindings.openDecoderFile(sampleFile.toString(), NativeFlacContainer.NATIVE.nativeCode)

        try {
            assertFailsWith<IllegalArgumentException> {
                NativeBindings.decodeDecoderRange(handle, 0, -1, CollectingConsumer())
            }
            assertFailsWith<IllegalArgumentException> {
                NativeBindings.decodeDecoderRange(handle, 0, 1, null)
            }
        } finally {
            NativeBindings.releaseDecoder(handle)
        }
    }

    @Test
    fun nativeSessionRangeDecodeKeepsHandleReusable() {
        FlacNativeLoader.load()
        val handle = NativeBindings.openDecoderFile(sampleFile.toString(), NativeFlacContainer.NATIVE.nativeCode)
        val first = CountingConsumer()
        val second = CountingConsumer()

        try {
            NativeBindings.decodeDecoderRange(handle, 0, 7, first)
            NativeBindings.decodeDecoderRange(handle, 0, 11, second)
        } finally {
            NativeBindings.releaseDecoder(handle)
        }

        assertEquals(7L, first.totalFrames)
        assertEquals(11L, second.totalFrames)
        assertTrue(first.completed)
        assertTrue(second.completed)
    }

    @Test
    fun nativeSessionDecodeRejectsConcurrentUse() {
        FlacNativeLoader.load()
        val handle = NativeBindings.openDecoderFile(sampleFile.toString(), NativeFlacContainer.NATIVE.nativeCode)
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                NativeBindings.decodeDecoderFrom(
                    handle,
                    0,
                    object : PcmConsumer {
                        override fun onStreamInfo(info: FlacStreamInfo) = Unit

                        override fun onPcmInterleaved(samples: IntArray, frames: Int) {
                            callbackEntered.countDown()
                            assertTrue(releaseCallback.await(5, TimeUnit.SECONDS))
                        }

                        override fun onComplete() = Unit
                    }
                )
            } catch (t: Throwable) {
                workerFailure.set(t)
            }
        }

        try {
            worker.start()
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
            assertFailsWith<IllegalStateException> {
                NativeBindings.decodeDecoderFrom(handle, 0, CollectingConsumer())
            }
        } finally {
            releaseCallback.countDown()
            worker.join(5_000)
            NativeBindings.releaseDecoder(handle)
        }

        assertTrue(!worker.isAlive)
        workerFailure.get()?.let { throw it }
    }

    @Test
    fun missingFileThrowsStableException() {
        val missing = sampleFile.resolveSibling("missing.flac")
        assertFailsWith<FlacDecodeException> {
            FlacMetadataReader().read(missing)
        }
    }

    @Test
    fun oggFlacMetadataReaderReturnsExpectedStructure() {
        val oggFile = createOggFlacFixture("jflac-ogg-metadata")

        try {
            val metadata = FlacMetadataReader().read(oggFile)

            assertEquals(8_000, metadata.streamInfo.sampleRate)
            assertEquals(1, metadata.streamInfo.channels)
            assertEquals(16, metadata.streamInfo.bitsPerSample)
            assertEquals(listOf("Ogg FLAC Fixture"), metadata.vorbisComment?.comments?.get("title"))
        } finally {
            Files.deleteIfExists(oggFile)
        }
    }

    @Test
    fun oggFlacFileDecodeReturnsPcm() {
        val oggFile = createOggFlacFixture("jflac-ogg-decode")

        try {
            val decoded = FlacDecoder().decode(oggFile)

            assertEquals(8_000, decoded.streamInfo.sampleRate)
            assertEquals(1, decoded.streamInfo.channels)
            assertEquals(16, decoded.streamInfo.bitsPerSample)
            assertEquals(80, decoded.totalFrames)
            assertEquals(80, decoded.interleavedSamples.size)
        } finally {
            Files.deleteIfExists(oggFile)
        }
    }

    @Test
    fun oggFlacFileRangeDecodeReturnsPcmWindow() {
        val oggFile = createOggFlacFixture("jflac-ogg-range")

        try {
            val chunks = ArrayList<FlacInterleavedPcmChunk>()
            val summary = FlacDecoder().decodeInterleaved(
                path = oggFile,
                firstSample = 7,
                maxFrames = 11,
                onChunk = FlacInterleavedPcmHandler { chunk -> chunks += chunk }
            )

            assertEquals(11L, summary.totalFrames)
            assertTrue(chunks.isNotEmpty())
            assertEquals(7L, chunks.first().firstFrameIndex)
            assertEquals(11, chunks.sumOf { chunk -> chunk.frames })
        } finally {
            Files.deleteIfExists(oggFile)
        }
    }

    @Test
    fun oggFlacFileDecodeSessionCanDecodeRange() {
        val oggFile = createOggFlacFixture("jflac-ogg-session")

        try {
            FlacDecoder().open(oggFile).use { session ->
                val chunks = ArrayList<FlacInterleavedPcmChunk>()
                val summary = session.decodeInterleaved(
                    firstSample = 13,
                    maxFrames = 9,
                    onChunk = FlacInterleavedPcmHandler { chunk -> chunks += chunk }
                )

                assertEquals(9L, summary.totalFrames)
                assertTrue(chunks.isNotEmpty())
                assertEquals(13L, chunks.first().firstFrameIndex)
                assertEquals(9, chunks.sumOf { chunk -> chunk.frames })
            }
        } finally {
            Files.deleteIfExists(oggFile)
        }
    }

    @Test
    fun truncatedFileFailsDecode() {
        // A file that starts like FLAC but ends early should make the native
        // decoder fail in a controlled way.
        val truncated = Files.createTempFile(sampleFile.name.removeSuffix(".flac"), "-truncated.flac")
        val bytes = Files.readAllBytes(sampleFile)
        Files.write(truncated, bytes.copyOf(128))

        assertFailsWith<FlacDecodeException> {
            FlacDecoder().decode(truncated, CollectingConsumer())
        }
    }

    @Test
    fun nativeDecodeRejectsNullConsumerBeforeEnteringCallbackFlow() {
        FlacNativeLoader.load()

        assertFailsWith<IllegalArgumentException> {
            NativeBindings.decodeFile(sampleFile.toString(), NativeFlacContainer.NATIVE.nativeCode, false, false, null)
        }
    }

    private class CollectingConsumer : PcmConsumer {
        /** Last STREAMINFO observed from the decoder. */
        var streamInfo: FlacStreamInfo? = null

        /** Running sum of decoded frame counts. */
        var totalFrames: Long = 0

        /** Counts malformed callback payloads for easier assertions. */
        var invalidChunkCount: Long = 0

        /** Set when the decoder completes normally. */
        var completed: Boolean = false

        override fun onStreamInfo(info: FlacStreamInfo) {
            streamInfo = info
        }

        override fun onPcmInterleaved(samples: IntArray, frames: Int) {
            val currentInfo = requireNotNull(streamInfo)
            // Every callback must deliver exactly `frames * channels` values
            // because the native shim interleaves channel-separated PCM first.
            if (samples.size != frames * currentInfo.channels) {
                invalidChunkCount += 1
            }
            totalFrames += frames.toLong()
        }

        override fun onComplete() {
            completed = true
        }
    }

    private class CountingConsumer : PcmConsumer {
        var totalFrames: Long = 0
        var completed: Boolean = false

        override fun onStreamInfo(info: FlacStreamInfo) = Unit

        override fun onPcmInterleaved(samples: IntArray, frames: Int) {
            totalFrames += frames.toLong()
        }

        override fun onComplete() {
            completed = true
        }
    }

    private fun assertInterleavedChunksEqual(
        expected: List<FlacInterleavedPcmChunk>,
        actual: List<FlacInterleavedPcmChunk>
    ) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (expectedChunk, actualChunk) ->
            assertEquals(expectedChunk.streamInfo, actualChunk.streamInfo)
            assertEquals(expectedChunk.frames, actualChunk.frames)
            assertEquals(expectedChunk.firstFrameIndex, actualChunk.firstFrameIndex)
            assertContentEquals(expectedChunk.interleavedSamples, actualChunk.interleavedSamples)
        }
    }

    private fun assertSamplesMatchFullAudio(
        full: FlacDecodedAudio,
        firstSample: Long,
        range: FlacDecodedAudio
    ) {
        val channels = full.streamInfo.channels
        val start = Math.multiplyExact(firstSample, channels.toLong())
        val end = Math.addExact(start, range.interleavedSamples.size.toLong())
        require(start <= Int.MAX_VALUE.toLong() && end <= Int.MAX_VALUE.toLong())
        val expected = full.interleavedSamples.copyOfRange(start.toInt(), end.toInt())
        assertContentEquals(expected, range.interleavedSamples)
    }

    private fun metadataOnlyFlacFixture(): ByteArray {
        val output = ByteArrayOutputStream()
        output.write("fLaC".toByteArray(Charsets.US_ASCII))
        output.writeMetadataBlock(
            type = FLAC_METADATA_TYPE_STREAMINFO,
            isLast = false,
            payload = streamInfoBlock()
        )
        output.writeMetadataBlock(
            type = FLAC_METADATA_TYPE_PADDING,
            isLast = false,
            payload = byteArrayOf(0x00, 0x00, 0x00)
        )
        output.writeMetadataBlock(
            type = FLAC_METADATA_TYPE_APPLICATION,
            isLast = false,
            payload = byteArrayOf(
                'T'.code.toByte(),
                'S'.code.toByte(),
                'T'.code.toByte(),
                '1'.code.toByte(),
                0x10,
                0x20
            )
        )
        output.writeMetadataBlock(
            type = FLAC_METADATA_TYPE_UNKNOWN_FIXTURE,
            isLast = false,
            payload = byteArrayOf(0x01, 0x02, 0x03)
        )
        output.writeMetadataBlock(
            type = FLAC_METADATA_TYPE_VORBIS_COMMENT,
            isLast = true,
            payload = vorbisCommentBlock()
        )
        return output.toByteArray()
    }

    private fun duplicateVorbisCommentFixture(): ByteArray {
        val output = ByteArrayOutputStream()
        output.write("fLaC".toByteArray(Charsets.US_ASCII))
        output.writeMetadataBlock(
            type = FLAC_METADATA_TYPE_STREAMINFO,
            isLast = false,
            payload = streamInfoBlock()
        )
        output.writeMetadataBlock(
            type = FLAC_METADATA_TYPE_VORBIS_COMMENT,
            isLast = false,
            payload = vorbisCommentBlock()
        )
        output.writeMetadataBlock(
            type = FLAC_METADATA_TYPE_VORBIS_COMMENT,
            isLast = true,
            payload = vorbisCommentBlock()
        )
        return output.toByteArray()
    }

    private fun streamInfoBlock(): ByteArray {
        val output = ByteArrayOutputStream(FLAC_STREAMINFO_LENGTH)
        output.writeUInt16BigEndian(16)
        output.writeUInt16BigEndian(16)
        output.writeUInt24BigEndian(0)
        output.writeUInt24BigEndian(0)

        /*
         * STREAMINFO packs sample rate, channel count minus one, bit depth
         * minus one, and total samples into one 64-bit big-endian field.
         */
        val sampleRate = 44_100L
        val channelsMinusOne = 1L
        val bitsPerSampleMinusOne = 15L
        val totalSamples = 0L
        val packedAudioFields =
            (sampleRate shl 44) or (channelsMinusOne shl 41) or (bitsPerSampleMinusOne shl 36) or totalSamples
        output.writeUInt64BigEndian(packedAudioFields)
        output.write(ByteArray(16))
        return output.toByteArray()
    }

    private fun vorbisCommentBlock(): ByteArray {
        val vendor = "fixture".toByteArray(Charsets.UTF_8)
        val entry = "TITLE=Fixture".toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream()
        // Vorbis comments store their string lengths in little-endian order.
        output.writeUInt32LittleEndian(vendor.size)
        output.write(vendor)
        output.writeUInt32LittleEndian(1)
        output.writeUInt32LittleEndian(entry.size)
        output.write(entry)
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.writeMetadataBlock(type: Int, isLast: Boolean, payload: ByteArray) {
        require(payload.size <= FLAC_METADATA_MAX_PAYLOAD_LENGTH)
        val typeByte = (if (isLast) FLAC_METADATA_LAST_BLOCK_FLAG else 0) or type
        write(typeByte)
        writeUInt24BigEndian(payload.size)
        write(payload)
    }

    private fun ByteArrayOutputStream.writeUInt16BigEndian(value: Int) {
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private fun ByteArrayOutputStream.writeUInt24BigEndian(value: Int) {
        write((value ushr 16) and 0xff)
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private fun ByteArrayOutputStream.writeUInt32LittleEndian(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private fun ByteArrayOutputStream.writeUInt64BigEndian(value: Long) {
        for (shift in 56 downTo 0 step 8) {
            write(((value ushr shift) and 0xff).toInt())
        }
    }
}
