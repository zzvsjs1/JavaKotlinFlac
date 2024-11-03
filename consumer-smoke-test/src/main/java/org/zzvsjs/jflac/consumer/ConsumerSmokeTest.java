package org.zzvsjs.jflac.consumer;

import org.zzvsjs.jflac.FlacAudioFormat;
import org.zzvsjs.jflac.FlacDecodedAudio;
import org.zzvsjs.jflac.FlacDecoder;
import org.zzvsjs.jflac.FlacEncoder;
import org.zzvsjs.jflac.FlacMetadataEditor;
import org.zzvsjs.jflac.FlacMetadata;
import org.zzvsjs.jflac.FlacMetadataReader;
import org.zzvsjs.jflac.FlacNativeLoader;
import org.zzvsjs.jflac.FlacStreamInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class ConsumerSmokeTest {
    private ConsumerSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one FLAC sample path argument.");
        }

        Path sample = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(sample)) {
            throw new IllegalArgumentException("Sample FLAC file does not exist: " + sample);
        }

        Path nativeDir = FlacNativeLoader.INSTANCE.load();
        if (!Files.isRegularFile(nativeDir.resolve("FLAC.dll"))
                || !Files.isRegularFile(nativeDir.resolve("jflac-jni.dll"))) {
            throw new IllegalStateException("Published jflac artefact did not extract the bundled Windows DLLs.");
        }

        FlacMetadata metadata = new FlacMetadataReader().read(sample);
        FlacStreamInfo info = metadata.getStreamInfo();
        if (info.getSampleRate() <= 0 || info.getChannels() <= 0 || info.getBitsPerSample() <= 0) {
            throw new IllegalStateException("Invalid STREAMINFO returned by published jflac artefact.");
        }
        if (info.getTotalSamples() <= 0) {
            throw new IllegalStateException("Consumer smoke fixture must contain at least one frame.");
        }

        long framesToDecode = Math.min(64L, info.getTotalSamples());
        FlacDecodedAudio decoded = new FlacDecoder().decode(sample, 0L, framesToDecode);
        if (decoded.getTotalFrames() != framesToDecode) {
            throw new IllegalStateException("Decoded frame count did not match requested smoke-test range.");
        }

        int expectedSamples = Math.toIntExact(framesToDecode * info.getChannels());
        if (decoded.getInterleavedSamples().length != expectedSamples) {
            throw new IllegalStateException("Decoded PCM length does not match frames * channels.");
        }

        verifyEncodeRoundTrip();

        System.out.printf(
                "jflac consumer smoke OK: %d Hz, %d channel(s), %d decoded frame(s)%n",
                info.getSampleRate(),
                info.getChannels(),
                decoded.getTotalFrames()
        );
    }

    private static void verifyEncodeRoundTrip() throws Exception {
        int frames = 32;
        FlacAudioFormat format = new FlacAudioFormat(
                22_050,
                2,
                16,
                Long.valueOf(frames)
        );
        int[] samples = deterministicPcm(frames, format);
        Path output = Files.createTempFile("jflac-consumer-encode", ".flac");

        try {
            new FlacEncoder().encode(output, format, samples);
            new FlacMetadataEditor().edit(output, session ->
                    session.setVorbisComments(Map.of("TITLE", List.of("Consumer smoke")))
            );

            FlacMetadata encodedMetadata = new FlacMetadataReader().read(output);
            FlacStreamInfo encodedInfo = encodedMetadata.getStreamInfo();
            if (encodedInfo.getSampleRate() != format.getSampleRate()) {
                throw new IllegalStateException("Encoded sample rate did not round-trip through metadata.");
            }
            if (encodedInfo.getChannels() != format.getChannels()) {
                throw new IllegalStateException("Encoded channel count did not round-trip through metadata.");
            }
            if (encodedInfo.getBitsPerSample() != format.getBitsPerSample()) {
                throw new IllegalStateException("Encoded bits-per-sample value did not round-trip through metadata.");
            }
            if (encodedInfo.getTotalSamples() != frames) {
                throw new IllegalStateException("Encoded frame count did not round-trip through metadata.");
            }
            if (!List.of("Consumer smoke").equals(encodedMetadata.getVorbisComment().getComments().get("TITLE"))) {
                throw new IllegalStateException("Edited Vorbis comments did not round-trip through metadata.");
            }

            FlacDecodedAudio encodedAudio = new FlacDecoder().decode(output);
            if (encodedAudio.getTotalFrames() != frames) {
                throw new IllegalStateException("Encoded frame count did not round-trip through decode.");
            }

            int expectedSamples = Math.multiplyExact(frames, format.getChannels());
            if (encodedAudio.getInterleavedSamples().length != expectedSamples) {
                throw new IllegalStateException("Encoded PCM length does not match frames * channels.");
            }
            if (!Arrays.equals(samples, encodedAudio.getInterleavedSamples())) {
                throw new IllegalStateException("Encoded PCM data did not round-trip exactly.");
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private static int[] deterministicPcm(int frames, FlacAudioFormat format) {
        int[] samples = new int[Math.multiplyExact(frames, format.getChannels())];
        int minSample = -(1 << (format.getBitsPerSample() - 1));
        int range = 1 << format.getBitsPerSample();

        for (int sampleIndex = 0; sampleIndex < samples.length; sampleIndex++) {
            int frame = sampleIndex / format.getChannels();
            int channel = sampleIndex % format.getChannels();
            samples[sampleIndex] = ((frame * 97 + channel * 151) % range) + minSample;
        }

        return samples;
    }
}
