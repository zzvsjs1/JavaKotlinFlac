package org.zzvsjs.jflac

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Timeout
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Random
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val LONG_FILE_PERFORMANCE_TAG = "long-file-performance"

/**
 * Exercises streaming memory behaviour and repeated seeking on a file whose
 * decoded IntArray representation is larger than the dedicated test JVM heap.
 *
 * Run this class through `./gradlew longFilePerformanceTest`. The task gives
 * the test worker a 96 MiB heap, while this fixture represents 144,000,000
 * bytes of interleaved Int PCM. A decoder that accidentally buffers the whole
 * file therefore fails with an out-of-memory error instead of turning a
 * retained-heap regression into a noisy sampling assertion.
 */
@Tag(LONG_FILE_PERFORMANCE_TAG)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation::class)
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class FlacLongFilePerformanceTest {
    private lateinit var longFile: Path

    @BeforeAll
    fun createLongFixture() {
        val elapsedNanos = measureNanoTime {
            longFile = Files.createTempFile("jflac-long-performance", ".flac")

            val seekPoints = generateSequence(0L) { previous ->
                (previous + SEEK_POINT_SPACING_FRAMES).takeIf { it < TOTAL_FRAMES }
            }.map { targetFrame ->
                /*
                 * libFLAC treats these values as a seek-table template. During
                 * encoding it replaces the zero offsets and sizes with the frame
                 * that contains each requested sample position.
                 */
                FlacSeekPoint(
                    sampleNumber = targetFrame,
                    streamOffset = 0L,
                    frameSamples = 0
                )
            }.toList()

            val format = FlacAudioFormat(
                sampleRate = SAMPLE_RATE,
                channels = CHANNELS,
                bitsPerSample = BITS_PER_SAMPLE,
                totalSamplesEstimate = TOTAL_FRAMES
            )
            val reusableChunk = IntArray(ENCODE_CHUNK_FRAMES * CHANNELS)

            FlacEncoder().open(
                output = longFile,
                format = format,
                metadata = FlacEncodingMetadata(
                    seekTables = listOf(FlacSeekTable(seekPoints))
                ),
                options = FlacEncodingOptions(
                    compressionLevel = 0,
                    verify = false,
                    blockSize = ENCODE_BLOCK_SIZE
                )
            ).use { session ->
                var firstFrame = 0L

                while (TOTAL_FRAMES - firstFrame >= ENCODE_CHUNK_FRAMES) {
                    fillPcmChunk(reusableChunk, firstFrame)
                    session.writeInterleaved(reusableChunk, ENCODE_CHUNK_FRAMES)

                    firstFrame += ENCODE_CHUNK_FRAMES
                }

                val remainingFrames = (TOTAL_FRAMES - firstFrame).toInt()
                if (remainingFrames > 0) {
                    val finalChunk = IntArray(remainingFrames * CHANNELS)
                    fillPcmChunk(finalChunk, firstFrame)
                    session.writeInterleaved(finalChunk, remainingFrames)
                }
            }
        }

        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
        val fixtureBudgetMillis = positiveLongProperty(
            name = "jflac.performance.fixtureBudgetMillis",
            defaultValue = FIXTURE_BUILD_BUDGET_MILLIS
        )

        check(elapsedMillis <= fixtureBudgetMillis) {
            "Building the long FLAC fixture took $elapsedMillis ms; " +
                "the budget is $fixtureBudgetMillis ms."
        }

        println(
            "jflac long-file fixture metric: frames=$TOTAL_FRAMES, " +
                "fileBytes=${Files.size(longFile)}, elapsedMillis=$elapsedMillis"
        )
    }

    @AfterAll
    fun deleteLongFixture() {
        if (::longFile.isInitialized) {
            Files.deleteIfExists(longFile)
        }
    }

    @Test
    @Order(1)
    fun streamingDecodeDoesNotMaterialiseLongFileAndReleasesRetainedHeap() {
        val logicalIntPcmBytes = Math.multiplyExact(
            Math.multiplyExact(TOTAL_FRAMES, CHANNELS.toLong()),
            Int.SIZE_BYTES.toLong()
        )
        val maximumHeapBytes = Runtime.getRuntime().maxMemory()

        assertTrue(
            logicalIntPcmBytes > maximumHeapBytes,
            "The dedicated test heap must be smaller than the full decoded Int PCM; " +
                "run this test through the longFilePerformanceTest task."
        )

        val memoryBean = ManagementFactory.getMemoryMXBean()
        val baselineHeapBytes = forceGcAndMeasureHeap()
        var emittedFrames = 0L
        var maximumChunkSamples = 0
        var observedHeapBytes = memoryBean.heapMemoryUsage.used

        val elapsedNanos = measureNanoTime {
            val summary = FlacDecoder().decodeInterleaved(
                path = longFile,
                onChunk = FlacInterleavedPcmHandler { chunk ->
                    assertEquals(emittedFrames, chunk.firstFrameIndex)
                    assertChunkBoundarySamples(chunk)

                    emittedFrames += chunk.frames
                    maximumChunkSamples = max(maximumChunkSamples, chunk.interleavedSamples.size)
                    observedHeapBytes = max(observedHeapBytes, memoryBean.heapMemoryUsage.used)
                }
            )

            assertEquals(TOTAL_FRAMES, summary.totalFrames)
        }

        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
        val retainedHeapBytes = forceGcAndMeasureHeap()
        val retainedHeapGrowthBytes = max(0L, retainedHeapBytes - baselineHeapBytes)
        val decodeBudgetMillis = positiveLongProperty(
            name = "jflac.performance.decodeBudgetMillis",
            defaultValue = STREAMING_DECODE_BUDGET_MILLIS
        )

        assertEquals(TOTAL_FRAMES, emittedFrames)
        assertTrue(maximumChunkSamples <= ENCODE_BLOCK_SIZE * CHANNELS)
        assertTrue(
            retainedHeapGrowthBytes <= MAX_RETAINED_HEAP_GROWTH_BYTES,
            "Streaming decode retained $retainedHeapGrowthBytes additional heap bytes; " +
                "the limit is $MAX_RETAINED_HEAP_GROWTH_BYTES bytes."
        )
        assertTrue(
            elapsedMillis <= decodeBudgetMillis,
            "Streaming the long FLAC took $elapsedMillis ms; " +
                "the budget is $decodeBudgetMillis ms."
        )

        println(
            "jflac long-file memory metric: frames=$emittedFrames, " +
                "fileBytes=${Files.size(longFile)}, logicalIntPcmBytes=$logicalIntPcmBytes, " +
                "maximumHeapBytes=$maximumHeapBytes, observedHeapBytes=$observedHeapBytes, " +
                "retainedHeapGrowthBytes=$retainedHeapGrowthBytes, " +
                "maximumChunkSamples=$maximumChunkSamples, elapsedMillis=$elapsedMillis"
        )
    }

    @Test
    @Order(2)
    fun reusableSessionMeetsRepeatedSeekBudgetAndReturnsCorrectPcm() {
        val encodedSeekPoints = FlacMetadataReader()
            .read(longFile)
            .seekTables
            .single()
            .points

        val expectedSeekPointCount = ((TOTAL_FRAMES - 1L) / SEEK_POINT_SPACING_FRAMES + 1L).toInt()

        assertEquals(expectedSeekPointCount, encodedSeekPoints.size)
        assertEquals(0L, encodedSeekPoints.first().sampleNumber)
        assertEquals(0L, encodedSeekPoints.first().streamOffset)
        assertTrue(encodedSeekPoints.drop(1).all { point -> point.streamOffset > 0L })
        assertTrue(encodedSeekPoints.all { point -> point.sampleNumber >= 0L })
        assertTrue(encodedSeekPoints.all { point -> point.frameSamples > 0 })
        assertTrue(
            encodedSeekPoints.zipWithNext().all { (first, second) ->
                first.sampleNumber < second.sampleNumber && first.streamOffset < second.streamOffset
            },
            "Encoder-populated seek points must remain strictly ordered."
        )

        val positions = deterministicSeekPositions(SEEK_OPERATION_COUNT + SEEK_WARM_UP_COUNT)
        var elapsedNanos = 0L
        var bytesRead = 0L
        var readCalls = 0L
        var positionChanges = 0L

        CountingSeekableByteChannel(
            Files.newByteChannel(longFile, StandardOpenOption.READ)
        ).use { input ->
            FlacDecoder().open(input).use { session ->
                positions.take(SEEK_WARM_UP_COUNT).forEach { firstFrame ->
                    assertDecodedWindow(session, firstFrame)
                }

                input.resetMetrics()
                elapsedNanos = measureNanoTime {
                    positions.drop(SEEK_WARM_UP_COUNT).forEach { firstFrame ->
                        assertDecodedWindow(session, firstFrame)
                    }
                }

                bytesRead = input.bytesRead
                readCalls = input.readCalls
                positionChanges = input.positionChanges
            }
        }

        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
        val averageSeekMicros = TimeUnit.NANOSECONDS.toMicros(elapsedNanos) / SEEK_OPERATION_COUNT
        val fileBytes = Files.size(longFile)
        val maximumAggregateReadBytes = Math.multiplyExact(fileBytes, MAX_AGGREGATE_SEEK_FILE_PASSES)
        val seekBudgetMillis = positiveLongProperty(
            name = "jflac.performance.seekBudgetMillis",
            defaultValue = REPEATED_SEEK_BUDGET_MILLIS
        )

        assertTrue(bytesRead > 0L)
        assertTrue(positionChanges > 0L)
        assertTrue(
            bytesRead <= maximumAggregateReadBytes,
            "$SEEK_OPERATION_COUNT seeks read $bytesRead bytes from a $fileBytes-byte file; " +
                "the aggregate limit is $maximumAggregateReadBytes bytes."
        )

        assertTrue(
            elapsedMillis <= seekBudgetMillis,
            "$SEEK_OPERATION_COUNT seeks took $elapsedMillis ms; " +
                "the budget is $seekBudgetMillis ms."
        )

        println(
            "jflac long-file seek metric: operations=$SEEK_OPERATION_COUNT, " +
                "windowFrames=$SEEK_WINDOW_FRAMES, elapsedMillis=$elapsedMillis, " +
                "averageSeekMicros=$averageSeekMicros, bytesRead=$bytesRead, " +
                "readCalls=$readCalls, positionChanges=$positionChanges"
        )
    }

    private fun fillPcmChunk(samples: IntArray, firstFrame: Long) {
        samples.indices.forEach { sampleIndex ->
            val frameOffset = sampleIndex / CHANNELS
            val channel = sampleIndex % CHANNELS

            samples[sampleIndex] = deterministicSample(firstFrame + frameOffset, channel)
        }
    }

    private fun assertChunkBoundarySamples(chunk: FlacInterleavedPcmChunk) {
        if (chunk.frames == 0) {
            return
        }

        for (channel in 0 until CHANNELS) {
            assertEquals(
                deterministicSample(chunk.firstFrameIndex, channel),
                chunk.interleavedSamples[channel]
            )
        }

        val lastFrame = chunk.firstFrameIndex + chunk.frames - 1L
        val lastFrameOffset = (chunk.frames - 1) * CHANNELS
        for (channel in 0 until CHANNELS) {
            assertEquals(
                deterministicSample(lastFrame, channel),
                chunk.interleavedSamples[lastFrameOffset + channel]
            )
        }
    }

    private fun deterministicSeekPositions(count: Int): List<Long> {
        val random = Random(SEEK_RANDOM_SEED)
        val maximumFirstFrame = TOTAL_FRAMES - SEEK_WINDOW_FRAMES

        return List(count) {
            random.nextLong(maximumFirstFrame + 1L)
        }
    }

    private fun forceGcAndMeasureHeap(): Long {
        repeat(3) {
            System.gc()
            Thread.sleep(50L)
        }

        return ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
    }

    private fun positiveLongProperty(name: String, defaultValue: Long): Long {
        val configured = System.getProperty(name)?.toLongOrNull()

        return configured?.takeIf { it > 0L } ?: defaultValue
    }

    private fun assertDecodedWindow(session: FlacDecodingSession, firstFrame: Long) {
        var emittedFrames = 0L

        val summary = session.decodeInterleaved(
            firstSample = firstFrame,
            maxFrames = SEEK_WINDOW_FRAMES,
            onChunk = FlacInterleavedPcmHandler { chunk ->
                assertEquals(firstFrame + emittedFrames, chunk.firstFrameIndex)
                assertChunkBoundarySamples(chunk)

                emittedFrames += chunk.frames
            }
        )

        assertEquals(SEEK_WINDOW_FRAMES, summary.totalFrames)
        assertEquals(SEEK_WINDOW_FRAMES, emittedFrames)
    }

    /**
     * Produces deterministic, random-access 16-bit PCM. The mixing steps make
     * the fixture difficult to compress, so seek tests cover a substantial
     * encoded file instead of a many-minute stream represented by a few KiB.
     */
    private fun deterministicSample(frame: Long, channel: Int): Int {
        var mixed = frame * 1_103_515_245L + (channel + 1L) * 12_345L
        mixed = mixed xor (mixed ushr 21)
        mixed *= 2_654_435_761L
        mixed = mixed xor (mixed ushr 17)

        return ((mixed ushr 16) and 0xFFFFL).toInt() - 32_768
    }

    private class CountingSeekableByteChannel(
        private val delegate: SeekableByteChannel
    ) : SeekableByteChannel {
        var bytesRead: Long = 0L
            private set

        var readCalls: Long = 0L
            private set

        var positionChanges: Long = 0L
            private set

        override fun read(destination: ByteBuffer): Int {
            val count = delegate.read(destination)

            readCalls++
            if (count > 0) {
                bytesRead += count
            }

            return count
        }

        override fun write(source: ByteBuffer): Int = delegate.write(source)

        override fun position(): Long = delegate.position()

        override fun position(newPosition: Long): SeekableByteChannel {
            delegate.position(newPosition)
            positionChanges++

            return this
        }

        override fun size(): Long = delegate.size()

        override fun truncate(size: Long): SeekableByteChannel {
            delegate.truncate(size)

            return this
        }

        override fun isOpen(): Boolean = delegate.isOpen

        override fun close() = delegate.close()

        fun resetMetrics() {
            bytesRead = 0L
            readCalls = 0L
            positionChanges = 0L
        }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val BITS_PER_SAMPLE = 16
        const val ENCODE_BLOCK_SIZE = 4_096
        const val ENCODE_CHUNK_FRAMES = 16_384
        const val TOTAL_FRAMES = 18_000_000L
        const val SEEK_POINT_SPACING_FRAMES = SAMPLE_RATE * 10L
        const val SEEK_WINDOW_FRAMES = 64L
        const val SEEK_WARM_UP_COUNT = 8
        const val SEEK_OPERATION_COUNT = 128
        const val SEEK_RANDOM_SEED = 0x4A_46_4C_41_43L

        const val MAX_AGGREGATE_SEEK_FILE_PASSES = 2L

        const val MAX_RETAINED_HEAP_GROWTH_BYTES = 24L * 1024L * 1024L
        const val FIXTURE_BUILD_BUDGET_MILLIS = 120_000L
        const val STREAMING_DECODE_BUDGET_MILLIS = 60_000L
        const val REPEATED_SEEK_BUDGET_MILLIS = 20_000L
    }
}
