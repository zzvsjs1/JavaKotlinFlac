package org.zzvsjs.jflac

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

private const val DEFAULT_DECODE_FIXTURE_FRAMES = 44_100
private const val OGG_FLAC_FIXTURE_BASE64 = """
T2dnUwACAAAAAAAAAACAGnKDAAAAAOUMOC4BM39GTEFDAQAAAWZMYUMAAAAiAkACQAAAAAAElQH0APAA
AAAAAAAAAAAAAAAAAAAAAAAAAE9nZ1MAAAAAAAAAAAAAgBpygwEAAABaeJ3IAVCEAABMDAAAAExhdmY2
Mi4zLjEwMAIAAAAaAAAAZW5jb2Rlcj1MYXZjNjIuMTEuMTAwIGZsYWMWAAAAdGl0bGU9T2dnIEZMQUMg
Rml4dHVyZU9nZ1MABFAAAAAAAAAAgBpygwIAAAD8m/WsAWL/+GQIAE8JTgAABWsKMg3FD7YPzQ4FCpTl
mJO/JGtbXhj3jqRBkn77gI6TffVzFBQs4acetz6Lr5N1u+UFHChZ57mLqrkyL7PZ4UIEDDxBDFUVyrJl
NWNFmChQkQ8lnYAQIg==
"""

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

internal fun createOggFlacFixture(prefix: String = "jflac-ogg-fixture"): Path {
    val output = Files.createTempFile(prefix, ".oga")
    Files.write(output, Base64.getMimeDecoder().decode(OGG_FLAC_FIXTURE_BASE64))
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
