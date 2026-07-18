package org.zzvsjs.jflac.consumer;

import org.zzvsjs.jflac.FlacAudioFormat;
import org.zzvsjs.jflac.FlacDecodedAudio;
import org.zzvsjs.jflac.FlacDecoder;
import org.zzvsjs.jflac.FlacEncoder;
import org.zzvsjs.jflac.FlacEncodingMetadata;
import org.zzvsjs.jflac.FlacMetadataEditor;
import org.zzvsjs.jflac.FlacMetadata;
import org.zzvsjs.jflac.FlacMetadataReader;
import org.zzvsjs.jflac.FlacNativeLoader;
import org.zzvsjs.jflac.FlacStreamInfo;
import org.zzvsjs.jflac.sound.JflacAudioFileTypes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

public final class ConsumerSmokeTest {
    private ConsumerSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one FLAC sample path argument.");
        }

        Path sample = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(sample)) {
            generateSampleFixture(sample);
        }

        Path nativeDir = FlacNativeLoader.INSTANCE.load();
        if (!Files.isDirectory(nativeDir)) {
            throw new IllegalStateException("Published jflac artefact did not extract its native runtime.");
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

        try (AudioInputStream javaSoundInput = AudioSystem.getAudioInputStream(sample.toFile())) {
            AudioFormat javaSoundFormat = javaSoundInput.getFormat();
            if (!AudioFormat.Encoding.PCM_SIGNED.equals(javaSoundFormat.getEncoding())) {
                throw new IllegalStateException("Java Sound FLAC provider did not return signed PCM.");
            }
            if ((int) javaSoundFormat.getSampleRate() != info.getSampleRate()) {
                throw new IllegalStateException("Java Sound sample rate does not match jflac metadata.");
            }
            if (javaSoundFormat.getChannels() != info.getChannels()) {
                throw new IllegalStateException("Java Sound channel count does not match jflac metadata.");
            }
            byte[] javaSoundBuffer = new byte[Math.max(1, javaSoundFormat.getFrameSize())];
            if (javaSoundInput.read(javaSoundBuffer) <= 0) {
                throw new IllegalStateException("Java Sound FLAC provider did not produce PCM bytes.");
            }
        }

        verifyJavaSoundWriteRoundTrip();
        verifyEncodeRoundTrip();

        System.out.printf(
                "jflac consumer smoke OK: %d Hz, %d channel(s), %d decoded frame(s)%n",
                info.getSampleRate(),
                info.getChannels(),
                decoded.getTotalFrames()
        );
    }

    private static void verifyJavaSoundWriteRoundTrip() throws Exception {
        int frames = 16;
        int channels = 2;
        byte[] pcm = new byte[frames * channels * 2];
        int[] samples = new int[frames * channels];
        for (int sampleIndex = 0; sampleIndex < samples.length; sampleIndex++) {
            int sample = ((sampleIndex * 37) % 65_536) - 32_768;
            samples[sampleIndex] = sample;
            pcm[sampleIndex * 2] = (byte) sample;
            pcm[sampleIndex * 2 + 1] = (byte) (sample >> 8);
        }

        AudioFormat pcmFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                22_050f,
                16,
                channels,
                channels * 2,
                22_050f,
                false
        );
        Path nativeOutput = Files.createTempFile("jflac-consumer-java-sound", ".flac");
        Path oggOutput = Files.createTempFile("jflac-consumer-java-sound", ".oga");
        try {
            AudioSystem.write(
                    new AudioInputStream(new ByteArrayInputStream(pcm), pcmFormat, frames),
                    JflacAudioFileTypes.FLAC,
                    nativeOutput.toFile()
            );
            AudioSystem.write(
                    new AudioInputStream(new ByteArrayInputStream(pcm), pcmFormat, frames),
                    JflacAudioFileTypes.OGG_FLAC,
                    oggOutput.toFile()
            );

            if (!Arrays.equals(samples, new FlacDecoder().decode(nativeOutput).getInterleavedSamples())) {
                throw new IllegalStateException("Java Sound native FLAC write did not round-trip PCM.");
            }
            if (!Arrays.equals(samples, new FlacDecoder().decode(oggOutput).getInterleavedSamples())) {
                throw new IllegalStateException("Java Sound Ogg FLAC write did not round-trip PCM.");
            }
            if (!Arrays.equals(samples, readJavaSoundPcmSamples(nativeOutput, frames, channels))) {
                throw new IllegalStateException("Java Sound native FLAC write/read did not round-trip PCM.");
            }
            if (!Arrays.equals(samples, readJavaSoundPcmSamples(oggOutput, frames, channels))) {
                throw new IllegalStateException("Java Sound Ogg FLAC write/read did not round-trip PCM.");
            }

            AudioFileFormat oggFormat = AudioSystem.getAudioFileFormat(oggOutput.toFile());
            if (!JflacAudioFileTypes.OGG_FLAC.equals(oggFormat.getType())) {
                throw new IllegalStateException("Java Sound Ogg FLAC read did not report OGG_FLAC type.");
            }
        } finally {
            Files.deleteIfExists(nativeOutput);
            Files.deleteIfExists(oggOutput);
        }
    }

    private static int[] readJavaSoundPcmSamples(Path input, int frames, int channels) throws Exception {
        try (AudioInputStream javaSoundInput = AudioSystem.getAudioInputStream(input.toFile())) {
            AudioFormat format = javaSoundInput.getFormat();
            if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding())) {
                throw new IllegalStateException("Java Sound FLAC read did not return signed PCM.");
            }
            if (format.getSampleSizeInBits() != 16) {
                throw new IllegalStateException("Java Sound FLAC read did not return 16-bit PCM.");
            }
            if (format.getChannels() != channels) {
                throw new IllegalStateException("Java Sound FLAC read channel count did not match input PCM.");
            }
            if (format.getFrameSize() != channels * 2) {
                throw new IllegalStateException("Java Sound FLAC read frame size did not match 16-bit stereo PCM.");
            }
            if (format.isBigEndian()) {
                throw new IllegalStateException("Java Sound FLAC read did not return little-endian PCM.");
            }

            byte[] pcm = javaSoundInput.readAllBytes();
            int sampleCount = Math.multiplyExact(frames, channels);
            int expectedBytes = Math.multiplyExact(sampleCount, 2);
            if (pcm.length != expectedBytes) {
                throw new IllegalStateException("Java Sound FLAC read PCM byte count did not match input PCM.");
            }

            int[] samples = new int[sampleCount];
            for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
                int byteIndex = sampleIndex * 2;
                samples[sampleIndex] = (short) ((pcm[byteIndex] & 0xff) | (pcm[byteIndex + 1] << 8));
            }
            return samples;
        }
    }

    private static void generateSampleFixture(Path sample) throws Exception {
        Path parent = sample.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        int frames = 44_100;
        FlacAudioFormat format = new FlacAudioFormat(
                44_100,
                2,
                16,
                Long.valueOf(frames)
        );
        int[] samples = deterministicPcm(frames, format);

        new FlacEncoder().encode(
                sample,
                format,
                samples,
                new FlacEncodingMetadata(Map.of("TITLE", List.of("Consumer smoke fixture")))
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
        Path channelOutput = Files.createTempFile("jflac-consumer-channel-encode", ".flac");

        try {
            new FlacEncoder().encode(output, format, samples);
            new FlacMetadataEditor().edit(output, session ->
                    session.setVorbisComments(Map.of("TITLE", List.of("Consumer edit")))
            );
            new FlacMetadataEditor().replace(
                    output,
                    new FlacEncodingMetadata(Map.of("TITLE", List.of("Consumer smoke")))
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

            try (SeekableByteChannel inputChannel = Files.newByteChannel(output, StandardOpenOption.READ)) {
                FlacDecodedAudio channelDecoded = new FlacDecoder().decode(inputChannel, 0L, frames);
                if (!Arrays.equals(samples, channelDecoded.getInterleavedSamples())) {
                    throw new IllegalStateException("Seekable channel decode did not round-trip exactly.");
                }
            }

            try (SeekableByteChannel outputChannel = Files.newByteChannel(
                    channelOutput,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE
            )) {
                new FlacEncoder().encode(outputChannel, format, samples);
            }
            FlacMetadata channelMetadata = new FlacMetadataReader().read(channelOutput);
            if (channelMetadata.getStreamInfo().getTotalSamples() != frames) {
                throw new IllegalStateException("Seekable channel encode did not back-patch STREAMINFO.");
            }

            ByteArrayOutputStream streamOutput = new ByteArrayOutputStream();
            new FlacEncoder().encode(streamOutput, format, samples);
            FlacDecodedAudio streamDecoded = new FlacDecoder().decode(
                    new ByteArrayInputStream(streamOutput.toByteArray())
            );
            if (!Arrays.equals(samples, streamDecoded.getInterleavedSamples())) {
                throw new IllegalStateException("Stream FLAC encode/decode did not round-trip exactly.");
            }
        } finally {
            Files.deleteIfExists(output);
            Files.deleteIfExists(channelOutput);
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
