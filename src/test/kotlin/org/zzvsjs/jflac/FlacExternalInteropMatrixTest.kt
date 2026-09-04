package org.zzvsjs.jflac

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.fail

private const val EXTERNAL_INTEROP_TAG = "external-interop"
private const val EXTERNAL_INTEROP_SAMPLE_RATE = 48_000
private const val EXTERNAL_INTEROP_FRAMES = 257
private const val EXTERNAL_PROCESS_TIMEOUT_SECONDS = 30L
private const val INTEROP_COMMENT_KEY = "JFLAC_INTEROP"

private val EXTERNAL_INTEROP_BITS_PER_SAMPLE = listOf(8, 16, 24, 32)
private val EXTERNAL_INTEROP_CHANNEL_COUNTS = listOf(1, 2, 6, 8)

/**
 * Exercises every supported byte-aligned PCM width and the important mono,
 * stereo, surround, and maximum-channel layouts against independent tools.
 *
 * This class deliberately belongs to a dedicated JUnit tag. The Gradle
 * `externalInteropTest` task requires all three programs and fails before test
 * execution when one is unavailable; the ordinary unit-test task excludes the
 * tag so a missing program can never turn into a silent CI assumption skip.
 */
@Tag(EXTERNAL_INTEROP_TAG)
class FlacExternalInteropMatrixTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @TestFactory
    fun exactPcmRoundTripsAcrossExternalInteropMatrix(): List<DynamicTest> {
        /*
         * Resolve and execute each version command before creating dynamic
         * tests. A bad configured path therefore produces one clear discovery
         * failure instead of the same process-start error in all 32 cases.
         */
        val tools = ExternalInteropTools.requireAvailable()

        return EXTERNAL_INTEROP_BITS_PER_SAMPLE.flatMap { bitsPerSample ->
            EXTERNAL_INTEROP_CHANNEL_COUNTS.flatMap { channels ->
                InteropContainer.entries.map { container ->
                    val matrixCase = InteropMatrixCase(bitsPerSample, channels, container)

                    DynamicTest.dynamicTest(matrixCase.displayName) {
                        verifyMatrixCase(matrixCase, tools)
                    }
                }
            }
        }
    }

    @TestFactory
    fun ffmpegExactEncodingInteroperabilityForSupportedBitDepths(): List<DynamicTest> {
        val tools = ExternalInteropTools.requireAvailable()

        /*
         * FFmpeg's FLAC encoder promotes 8-bit input to 16-bit and currently
         * caps 32-bit input at 24-bit. Keep its encoder direction as a
         * separate exact-only matrix so those documented format conversions
         * cannot be mistaken for jFLAC sample corruption.
         */
        return listOf(16, 24).flatMap { bitsPerSample ->
            EXTERNAL_INTEROP_CHANNEL_COUNTS.flatMap { channels ->
                InteropContainer.entries.map { container ->
                    val matrixCase = InteropMatrixCase(bitsPerSample, channels, container)

                    DynamicTest.dynamicTest("FFmpeg encode x ${matrixCase.displayName}") {
                        verifyFfmpegEncoderCase(matrixCase, tools.ffmpeg)
                    }
                }
            }
        }
    }

    private fun verifyMatrixCase(matrixCase: InteropMatrixCase, tools: ExternalInteropTools) {
        val format = FlacAudioFormat(
            sampleRate = EXTERNAL_INTEROP_SAMPLE_RATE,
            channels = matrixCase.channels,
            bitsPerSample = matrixCase.bitsPerSample,
            totalSamplesEstimate = EXTERNAL_INTEROP_FRAMES.toLong()
        )
        val expectedSamples = createInteropPcm(format)
        val expectedRawPcm = encodeLittleEndianPcm(expectedSamples, matrixCase.bitsPerSample)
        val sourceRaw = temporaryDirectory.resolve("${matrixCase.fileStem}-source.raw")
        val officialFlac = temporaryDirectory.resolve(
            "${matrixCase.fileStem}-official.${matrixCase.container.fileExtension}"
        )
        val jflacOutput = temporaryDirectory.resolve(
            "${matrixCase.fileStem}-jflac.${matrixCase.container.fileExtension}"
        )
        val flacDecodedRaw = temporaryDirectory.resolve("${matrixCase.fileStem}-flac-decoded.raw")
        val ffmpegDecodedRaw = temporaryDirectory.resolve("${matrixCase.fileStem}-ffmpeg-decoded.raw")
        Files.write(sourceRaw, expectedRawPcm)

        encodeWithOfficialFlac(tools.flac, matrixCase, sourceRaw, officialFlac)

        if (matrixCase.container == InteropContainer.NATIVE) {
            writeMetadataWithMetaflac(tools.metaflac, matrixCase, officialFlac)
        }

        assertDecodedByJflac(matrixCase, officialFlac, expectedSamples)

        if (matrixCase.container == InteropContainer.NATIVE) {
            val externalMetadata = FlacMetadataReader().read(officialFlac)

            assertEquals(
                listOf(matrixCase.externalMetadataValue),
                externalMetadata.vorbisComment?.comments?.get(INTEROP_COMMENT_KEY),
                "metaflac-written comment for ${matrixCase.displayName}"
            )
        }

        FlacEncoder().encode(
            output = jflacOutput,
            format = format,
            samples = expectedSamples,
            metadata = FlacEncodingMetadata(
                comments = mapOf(INTEROP_COMMENT_KEY to listOf(matrixCase.jflacMetadataValue))
            ),
            options = FlacEncodingOptions(
                compressionLevel = 0,
                verify = true,
                container = matrixCase.container.jflacContainer
            )
        )

        decodeWithOfficialFlac(tools.flac, matrixCase, jflacOutput, flacDecodedRaw)
        decodeWithFfmpeg(tools.ffmpeg, matrixCase, jflacOutput, ffmpegDecodedRaw)

        if (matrixCase.container == InteropContainer.NATIVE) {
            assertMetadataReadableByMetaflac(tools.metaflac, matrixCase, jflacOutput)
        }

        assertContentEquals(
            expectedRawPcm,
            Files.readAllBytes(flacDecodedRaw),
            "official flac decoder PCM for ${matrixCase.displayName}"
        )

        assertContentEquals(
            expectedRawPcm,
            Files.readAllBytes(ffmpegDecodedRaw),
            "FFmpeg decoder PCM for ${matrixCase.displayName}"
        )
    }

    private fun verifyFfmpegEncoderCase(matrixCase: InteropMatrixCase, ffmpeg: String) {
        val format = FlacAudioFormat(
            sampleRate = EXTERNAL_INTEROP_SAMPLE_RATE,
            channels = matrixCase.channels,
            bitsPerSample = matrixCase.bitsPerSample,
            totalSamplesEstimate = EXTERNAL_INTEROP_FRAMES.toLong()
        )
        val expectedSamples = createInteropPcm(format)
        val sourceRaw = temporaryDirectory.resolve("${matrixCase.fileStem}-ffmpeg-source.raw")
        val ffmpegFlac = temporaryDirectory.resolve(
            "${matrixCase.fileStem}-ffmpeg.${matrixCase.container.fileExtension}"
        )
        Files.write(sourceRaw, encodeLittleEndianPcm(expectedSamples, matrixCase.bitsPerSample))

        encodeWithFfmpeg(ffmpeg, matrixCase, sourceRaw, ffmpegFlac)
        assertDecodedByJflac(matrixCase, ffmpegFlac, expectedSamples)
    }

    private fun encodeWithOfficialFlac(
        executable: String,
        matrixCase: InteropMatrixCase,
        sourceRaw: Path,
        output: Path
    ) {
        val containerArguments = if (matrixCase.container == InteropContainer.OGG) {
            listOf("--ogg")
        } else {
            emptyList()
        }

        runExternalProcess(
            description = "official flac encode for ${matrixCase.displayName}",
            command = buildList {
                add(executable)
                add("--silent")
                add("--force")
                add("--verify")
                add("-0")
                addAll(containerArguments)
                add("--force-raw-format")
                add("--endian=little")
                add("--sign=signed")
                add("--channel-map=none")
                add("--channels=${matrixCase.channels}")
                add("--bps=${matrixCase.bitsPerSample}")
                add("--sample-rate=$EXTERNAL_INTEROP_SAMPLE_RATE")
                add("--output-name=${output.toAbsolutePath()}")
                add(sourceRaw.toAbsolutePath().toString())
            }
        )
    }

    private fun encodeWithFfmpeg(
        executable: String,
        matrixCase: InteropMatrixCase,
        sourceRaw: Path,
        output: Path
    ) {
        runExternalProcess(
            description = "FFmpeg FLAC encode for ${matrixCase.displayName}",
            command = listOf(
                executable,
                "-hide_banner",
                "-nostdin",
                "-loglevel",
                "error",
                "-y",
                "-f",
                matrixCase.ffmpegPcmFormat,
                "-ar",
                EXTERNAL_INTEROP_SAMPLE_RATE.toString(),
                "-ac",
                matrixCase.channels.toString(),
                "-i",
                sourceRaw.toAbsolutePath().toString(),
                "-map",
                "0:a:0",
                "-c:a",
                "flac",
                "-compression_level",
                "0",
                "-bits_per_raw_sample",
                matrixCase.bitsPerSample.toString(),
                "-f",
                matrixCase.container.ffmpegMuxer,
                output.toAbsolutePath().toString()
            )
        )
    }

    private fun decodeWithOfficialFlac(
        executable: String,
        matrixCase: InteropMatrixCase,
        input: Path,
        output: Path
    ) {
        runExternalProcess(
            description = "official flac decode for ${matrixCase.displayName}",
            command = listOf(
                executable,
                "--silent",
                "--force",
                "--decode",
                "--force-raw-format",
                "--endian=little",
                "--sign=signed",
                "--channel-map=none",
                "--output-name=${output.toAbsolutePath()}",
                input.toAbsolutePath().toString()
            )
        )
    }

    private fun decodeWithFfmpeg(
        executable: String,
        matrixCase: InteropMatrixCase,
        input: Path,
        output: Path
    ) {
        runExternalProcess(
            description = "FFmpeg decode for ${matrixCase.displayName}",
            command = listOf(
                executable,
                "-hide_banner",
                "-nostdin",
                "-loglevel",
                "error",
                "-y",
                "-i",
                input.toAbsolutePath().toString(),
                "-map",
                "0:a:0",
                "-c:a",
                matrixCase.ffmpegPcmCodec,
                "-f",
                matrixCase.ffmpegPcmFormat,
                output.toAbsolutePath().toString()
            )
        )
    }

    private fun writeMetadataWithMetaflac(
        executable: String,
        matrixCase: InteropMatrixCase,
        input: Path
    ) {
        runExternalProcess(
            description = "metaflac metadata write for ${matrixCase.displayName}",
            command = listOf(
                executable,
                "--remove-tag=$INTEROP_COMMENT_KEY",
                "--set-tag=$INTEROP_COMMENT_KEY=${matrixCase.externalMetadataValue}",
                input.toAbsolutePath().toString()
            )
        )
    }

    private fun assertMetadataReadableByMetaflac(
        executable: String,
        matrixCase: InteropMatrixCase,
        input: Path
    ) {
        val output = runExternalProcess(
            description = "metaflac metadata read for ${matrixCase.displayName}",
            command = listOf(
                executable,
                "--show-sample-rate",
                "--show-channels",
                "--show-bps",
                "--show-total-samples",
                "--show-tag=$INTEROP_COMMENT_KEY",
                input.toAbsolutePath().toString()
            )
        )
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()

        assertContentEquals(
            listOf(
                EXTERNAL_INTEROP_SAMPLE_RATE.toString(),
                matrixCase.channels.toString(),
                matrixCase.bitsPerSample.toString(),
                EXTERNAL_INTEROP_FRAMES.toString(),
                "$INTEROP_COMMENT_KEY=${matrixCase.jflacMetadataValue}"
            ),
            lines,
            "metaflac STREAMINFO and Vorbis comment for ${matrixCase.displayName}"
        )
    }

    private fun assertDecodedByJflac(
        matrixCase: InteropMatrixCase,
        input: Path,
        expectedSamples: IntArray
    ) {
        val decoded = FlacDecoder().decode(input)

        assertEquals(
            EXTERNAL_INTEROP_SAMPLE_RATE,
            decoded.streamInfo.sampleRate,
            "sample rate for ${matrixCase.displayName}"
        )

        assertEquals(
            matrixCase.channels,
            decoded.streamInfo.channels,
            "channel count for ${matrixCase.displayName}"
        )

        assertEquals(
            matrixCase.bitsPerSample,
            decoded.streamInfo.bitsPerSample,
            "bit depth for ${matrixCase.displayName}"
        )

        assertEquals(
            EXTERNAL_INTEROP_FRAMES.toLong(),
            decoded.totalFrames,
            "frame count for ${matrixCase.displayName}"
        )

        assertContentEquals(
            expectedSamples,
            decoded.interleavedSamples,
            "jFLAC decoder PCM for ${matrixCase.displayName}"
        )
    }
}

private data class InteropMatrixCase(
    val bitsPerSample: Int,
    val channels: Int,
    val container: InteropContainer
) {
    val displayName: String = "$bitsPerSample-bit x $channels-channel x ${container.displayName}"
    val fileStem: String = "${bitsPerSample}bit-${channels}ch-${container.name.lowercase()}"
    val ffmpegPcmFormat: String = if (bitsPerSample == 8) "s8" else "s${bitsPerSample}le"
    val ffmpegPcmCodec: String = if (bitsPerSample == 8) "pcm_s8" else "pcm_s${bitsPerSample}le"
    val externalMetadataValue: String = "external-$fileStem"
    val jflacMetadataValue: String = "jflac-$fileStem"
}

private enum class InteropContainer(
    val displayName: String,
    val fileExtension: String,
    val jflacContainer: FlacEncodingContainer,
    val ffmpegMuxer: String
) {
    NATIVE("Native FLAC", "flac", FlacEncodingContainer.NATIVE, "flac"),
    OGG("Ogg FLAC", "oga", FlacEncodingContainer.OGG, "ogg")
}

private data class ExternalInteropTools(
    val flac: String,
    val ffmpeg: String,
    val metaflac: String
) {
    companion object {
        fun requireAvailable(): ExternalInteropTools {
            val tools = ExternalInteropTools(
                flac = configuredExecutable(
                    propertyName = "jflac.external.flac",
                    environmentName = "JFLAC_EXTERNAL_FLAC",
                    defaultCommand = "flac"
                ),
                ffmpeg = configuredExecutable(
                    propertyName = "jflac.external.ffmpeg",
                    environmentName = "JFLAC_EXTERNAL_FFMPEG",
                    defaultCommand = "ffmpeg"
                ),
                metaflac = configuredExecutable(
                    propertyName = "jflac.external.metaflac",
                    environmentName = "JFLAC_EXTERNAL_METAFLAC",
                    defaultCommand = "metaflac"
                )
            )

            runExternalProcess("official flac availability check", listOf(tools.flac, "--version"))
            runExternalProcess("FFmpeg availability check", listOf(tools.ffmpeg, "-version"))
            /*
             * Official metaflac 1.5.0 on Windows can terminate in its isolated
             * --version branch even though normal metadata operations work.
             * --help exercises the same executable and reports its version in
             * the banner without relying on that upstream-only failure path.
             */
            runExternalProcess("metaflac availability check", listOf(tools.metaflac, "--help"))

            return tools
        }
    }
}

private fun configuredExecutable(
    propertyName: String,
    environmentName: String,
    defaultCommand: String
): String {
    val configured = System.getProperty(propertyName)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: System.getenv(environmentName)?.trim()?.takeIf(String::isNotEmpty)
        ?: defaultCommand

    return configured.removeSurrounding("\"")
}

private fun createInteropPcm(format: FlacAudioFormat): IntArray {
    val minimumSample = if (format.bitsPerSample == 32) {
        Int.MIN_VALUE
    } else {
        -(1 shl (format.bitsPerSample - 1))
    }
    val maximumSample = if (format.bitsPerSample == 32) {
        Int.MAX_VALUE
    } else {
        (1 shl (format.bitsPerSample - 1)) - 1
    }

    return IntArray(EXTERNAL_INTEROP_FRAMES * format.channels) { sampleIndex ->
        when (sampleIndex % 29) {
            0 -> minimumSample
            1 -> maximumSample
            2 -> 0
            3 -> -1
            4 -> 1
            else -> {
                val frame = sampleIndex / format.channels
                val channel = sampleIndex % format.channels
                val mixed = (
                    frame.toLong() * 1_103_515_245L +
                        channel.toLong() * 12_345L +
                        format.bitsPerSample.toLong() * 2_654_435_761L
                    ).toInt()
                val signedNoise = mixed xor (mixed ushr 16)

                if (format.bitsPerSample == 32) {
                    signedNoise
                } else {
                    signedNoise shr (32 - format.bitsPerSample)
                }
            }
        }
    }
}

private fun encodeLittleEndianPcm(samples: IntArray, bitsPerSample: Int): ByteArray {
    val bytesPerSample = bitsPerSample / Byte.SIZE_BITS
    val bytes = ByteArray(samples.size * bytesPerSample)
    var outputIndex = 0

    samples.forEach { sample ->
        repeat(bytesPerSample) { byteIndex ->
            bytes[outputIndex] = (sample ushr (byteIndex * Byte.SIZE_BITS)).toByte()
            outputIndex++
        }
    }

    return bytes
}

private fun runExternalProcess(description: String, command: List<String>): String {
    val process = try {
        ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
    } catch (failure: IOException) {
        fail(
            "$description could not start '${formatCommand(command)}'. " +
                "Configure the dedicated Gradle task's executable property.",
            failure
        )
    }
    val outputFuture = CompletableFuture.supplyAsync {
        process.inputStream.use { input ->
            String(input.readAllBytes(), StandardCharsets.UTF_8)
        }
    }

    if (!process.waitFor(EXTERNAL_PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroy()

        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }

        fail(
            "$description exceeded ${EXTERNAL_PROCESS_TIMEOUT_SECONDS}s: ${formatCommand(command)}"
        )
    }

    val output = try {
        outputFuture.get(5, TimeUnit.SECONDS)
    } catch (failure: TimeoutException) {
        fail("$description did not finish draining process output.", failure)
    } catch (failure: Exception) {
        fail("$description could not read process output.", failure)
    }

    if (process.exitValue() != 0) {
        fail(
            buildString {
                append(description)
                append(" failed with exit code ")
                append(process.exitValue())
                append(": ")
                appendLine(formatCommand(command))
                append(output.trim())
            }
        )
    }

    return output
}

private fun formatCommand(command: List<String>): String {
    return command.joinToString(" ") { argument ->
        if (argument.any(Char::isWhitespace)) "\"$argument\"" else argument
    }
}
