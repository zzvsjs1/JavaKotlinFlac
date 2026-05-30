package org.zzvsjs.jflac

import java.nio.file.Files
import java.nio.file.Path

private const val DEFAULT_DECODE_FIXTURE_FRAMES = 44_100

internal fun createDecodeFixture(prefix: String = "jflac-decode-fixture"): Path {
    val frames = DEFAULT_DECODE_FIXTURE_FRAMES
    val format = FlacAudioFormat(
        sampleRate = 44_100,
        channels = 2,
        bitsPerSample = 16,
        totalSamplesEstimate = frames.toLong()
    )
    val output = Files.createTempFile(prefix, ".flac")

    FlacEncoder().encode(
        output = output,
        format = format,
        samples = deterministicFixturePcm(frames, format),
        metadata = FlacEncodingMetadata(
            comments = mapOf("TITLE" to listOf("Local integration fixture")),
            paddingBlocks = listOf(FlacPaddingBlock(length = 4_096))
        )
    )

    return output
}

internal fun deterministicFixturePcm(
    frames: Int,
    format: FlacAudioFormat,
    frameOffset: Int = 0
): IntArray {
    val minSample = -(1 shl (format.bitsPerSample - 1))
    val range = 1 shl format.bitsPerSample
    return IntArray(frames * format.channels) { sampleIndex ->
        val frame = (sampleIndex / format.channels) + frameOffset
        val channel = sampleIndex % format.channels
        ((frame * 73 + channel * 149) % range) + minSample
    }
}
