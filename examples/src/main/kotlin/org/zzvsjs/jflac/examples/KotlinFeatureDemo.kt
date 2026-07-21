package org.zzvsjs.jflac.examples

import org.zzvsjs.jflac.FlacAudioFormat
import org.zzvsjs.jflac.FlacDecoder
import org.zzvsjs.jflac.FlacEncoder
import org.zzvsjs.jflac.FlacEncodingMetadata
import org.zzvsjs.jflac.FlacInterleavedPcmHandler
import org.zzvsjs.jflac.FlacMetadataEditor
import org.zzvsjs.jflac.FlacMetadataReader
import org.zzvsjs.jflac.FlacStreamInfo
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.math.min

private const val demoFrames = 2_048
private const val previewFrames = 4_096

fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: KotlinFeatureDemo <input.flac>" }

    val input = Path.of(args[0]).normalize()
    require(Files.isRegularFile(input)) { "Input FLAC file does not exist: $input" }

    val outputDir = Path.of("build", "jflac-feature-demo", "kotlin").normalize()
    Files.createDirectories(outputDir)

    val reader = FlacMetadataReader()
    val metadata = reader.read(input)
    val streamInfo = metadata.streamInfo
    val framesToRead = choosePreviewFrames(streamInfo)

    printStreamInfo("input", streamInfo)
    println("Vorbis comments: ${metadata.vorbisComment}")

    val range = FlacDecoder().decode(input, firstSample = 0, maxFrames = framesToRead)
    println("Path range decode: ${range.totalFrames} frame(s), ${range.interleavedSamples.size} sample value(s)")

    Files.newInputStream(input).use { stream: InputStream ->
        var printedChunks = 0
        val summary = FlacDecoder().decodeInterleaved(
            input = stream,
            onChunk = { chunk ->
                if (printedChunks < 3) {
                    println(
                        "InputStream chunk ${printedChunks + 1}: first frame ${chunk.firstFrameIndex}, " +
                                "${chunk.frames} frame(s)"
                    )
                }

                printedChunks += 1
            }
        )
        println("InputStream decode summary: ${summary.totalFrames} frame(s)")
    }

    Files.newByteChannel(input, StandardOpenOption.READ).use { channel: SeekableByteChannel ->
        FlacDecoder().open(channel).use { session ->
            val summary = session.decodeInterleaved(
                firstSample = 0,
                maxFrames = framesToRead,
                onChunk = { chunk ->
                    println("Seekable channel session chunk: first frame ${chunk.firstFrameIndex}, ${chunk.frames} frame(s)")
                }
            )
            println("Seekable channel session summary: ${summary.totalFrames} frame(s)")
        }
    }

    val demoFormat = FlacAudioFormat(
        sampleRate = 44_100,
        channels = 2,
        bitsPerSample = 16,
        totalSamplesEstimate = demoFrames.toLong()
    )
    val demoSamples = deterministicPcm(demoFrames, demoFormat)

    val fileOutput = outputDir.resolve("kotlin-file-session.flac")
    FlacEncoder().open(
        output = fileOutput,
        format = demoFormat,
        metadata = FlacEncodingMetadata(comments = mapOf("TITLE" to listOf("jflac Kotlin file session demo")))
    ).use { session ->
        val halfFrames = demoFrames / 2
        session.writeInterleaved(demoSamples.copyOfRange(0, halfFrames * demoFormat.channels), halfFrames)
        session.writeInterleaved(
            demoSamples.copyOfRange(halfFrames * demoFormat.channels, demoSamples.size),
            demoFrames - halfFrames
        )
    }

    printStreamInfo("encoded file session", reader.read(fileOutput).streamInfo)

    val streamOutput = outputDir.resolve("kotlin-output-stream.flac")
    Files.newOutputStream(streamOutput).use { output ->
        FlacEncoder().encode(
            output = output,
            format = demoFormat,
            samples = demoSamples,
            metadata = FlacEncodingMetadata(comments = mapOf("TITLE" to listOf("jflac Kotlin OutputStream demo")))
        )
    }

    Files.newInputStream(streamOutput).use { encodedStream ->
        val decoded = FlacDecoder().decode(encodedStream)
        println("OutputStream encode then InputStream decode: ${decoded.totalFrames} frame(s)")
    }

    val channelOutput = outputDir.resolve("kotlin-seekable-channel.flac")
    Files.newByteChannel(
        channelOutput,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.READ,
        StandardOpenOption.WRITE
    ).use { channel ->
        FlacEncoder().encode(
            output = channel,
            format = demoFormat,
            samples = demoSamples,
            metadata = FlacEncodingMetadata(comments = mapOf("TITLE" to listOf("jflac Kotlin seekable channel demo")))
        )
    }

    printStreamInfo("encoded seekable channel", reader.read(channelOutput).streamInfo)

    val editedCopy = outputDir.resolve("kotlin-edited-copy.flac")
    Files.copy(input, editedCopy, StandardCopyOption.REPLACE_EXISTING)
    FlacMetadataEditor().edit(editedCopy) { session ->
        session.setVorbisComments(mapOf("TITLE" to listOf("Edited by KotlinFeatureDemo")))
    }

    println("Edited copy comments: ${reader.read(editedCopy).vorbisComment?.comments}")

    println("Example output directory: $outputDir")
}

private fun choosePreviewFrames(streamInfo: FlacStreamInfo): Long {
    return if (streamInfo.totalSamples > 0L) {
        min(previewFrames.toLong(), streamInfo.totalSamples)
    } else {
        previewFrames.toLong()
    }
}

private fun printStreamInfo(label: String, info: FlacStreamInfo) {
    println(
        "$label STREAMINFO: ${info.sampleRate} Hz, ${info.channels} channel(s), " +
                "${info.bitsPerSample} bit, total samples ${info.totalSamples}"
    )
}

private fun deterministicPcm(frames: Int, format: FlacAudioFormat): IntArray {
    val samples = IntArray(Math.multiplyExact(frames, format.channels))
    val minSample = -(1 shl (format.bitsPerSample - 1))
    val range = 1 shl format.bitsPerSample

    for (sampleIndex in samples.indices) {
        val frame = sampleIndex / format.channels
        val channel = sampleIndex % format.channels
        samples[sampleIndex] = ((frame * 97 + channel * 151) % range) + minSample
    }

    return samples
}
