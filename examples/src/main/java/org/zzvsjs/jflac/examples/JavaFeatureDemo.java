package org.zzvsjs.jflac.examples;

import org.jetbrains.annotations.NotNull;
import org.zzvsjs.jflac.FlacAudioFormat;
import org.zzvsjs.jflac.FlacDecodeAdapter;
import org.zzvsjs.jflac.FlacDecodeSummary;
import org.zzvsjs.jflac.FlacDecodedAudio;
import org.zzvsjs.jflac.FlacDecoder;
import org.zzvsjs.jflac.FlacDecodingSession;
import org.zzvsjs.jflac.FlacEncoder;
import org.zzvsjs.jflac.FlacEncodingMetadata;
import org.zzvsjs.jflac.FlacEncodingSession;
import org.zzvsjs.jflac.FlacInterleavedPcmChunk;
import org.zzvsjs.jflac.FlacMetadata;
import org.zzvsjs.jflac.FlacMetadataEditor;
import org.zzvsjs.jflac.FlacMetadataReader;
import org.zzvsjs.jflac.FlacStreamInfo;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class JavaFeatureDemo {
    private static final int DEMO_FRAMES = 2_048;
    private static final int PREVIEW_FRAMES = 4_096;

    private JavaFeatureDemo() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: JavaFeatureDemo <input.flac>");
        }

        Path input = Path.of(args[0]).normalize();
        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("Input FLAC file does not exist: " + input);
        }

        Path outputDir = Path.of("build", "jflac-feature-demo", "java").normalize();
        Files.createDirectories(outputDir);

        FlacMetadata metadata = new FlacMetadataReader().read(input);
        FlacStreamInfo streamInfo = metadata.getStreamInfo();
        long previewFrames = choosePreviewFrames(streamInfo);

        printStreamInfo("input", streamInfo);
        System.out.println("Vorbis comments: " + metadata.getVorbisComment());

        FlacDecodedAudio range = new FlacDecoder().decode(input, 0L, previewFrames);
        System.out.printf("Path range decode: %,d frame(s), %,d sample value(s)%n",
                range.getTotalFrames(),
                range.getInterleavedSamples().length);

        try (InputStream stream = Files.newInputStream(input)) {
            FlacDecodeSummary streamSummary = new FlacDecoder().decode(
                    stream,
                    new FlacDecodeAdapter() {
                        private int printedChunks;

                        @Override
                        public void onInterleavedPcm(@NotNull FlacInterleavedPcmChunk chunk) {
                            if (printedChunks < 3) {
                                System.out.printf(
                                        "InputStream chunk %d: first frame %,d, %,d frame(s)%n",
                                        printedChunks + 1,
                                        chunk.getFirstFrameIndex(),
                                        chunk.getFrames()
                                );
                            }
                            printedChunks += 1;
                        }
                    }
            );
            System.out.printf("InputStream decode summary: %,d frame(s)%n", streamSummary.getTotalFrames());
        }

        try (SeekableByteChannel channel = Files.newByteChannel(input, StandardOpenOption.READ);
             FlacDecodingSession session = new FlacDecoder().open(channel)) {
            FlacDecodeSummary channelSummary = session.decodeInterleaved(
                    0L,
                    previewFrames,
                    chunk -> System.out.printf(
                            "Seekable channel session chunk: first frame %,d, %,d frame(s)%n",
                            chunk.getFirstFrameIndex(),
                            chunk.getFrames()
                    ),
                    null,
                    null
            );
            System.out.printf("Seekable channel session summary: %,d frame(s)%n", channelSummary.getTotalFrames());
        }

        FlacAudioFormat demoFormat = new FlacAudioFormat(44_100, 2, 16, (long) DEMO_FRAMES);
        int[] demoSamples = deterministicPcm(demoFormat);

        Path fileOutput = outputDir.resolve("java-file-session.flac");
        try (FlacEncodingSession session = new FlacEncoder().open(
                fileOutput,
                demoFormat,
                new FlacEncodingMetadata(Map.of("TITLE", List.of("jflac Java file session demo")))
        )) {
            int halfFrames = DEMO_FRAMES / 2;
            session.writeInterleaved(
                    Arrays.copyOfRange(demoSamples, 0, halfFrames * demoFormat.getChannels()),
                    halfFrames
            );
            session.writeInterleaved(
                    Arrays.copyOfRange(
                            demoSamples,
                            halfFrames * demoFormat.getChannels(),
                            demoSamples.length
                    ),
                    DEMO_FRAMES - halfFrames
            );
        }
        printStreamInfo("encoded file session", new FlacMetadataReader().read(fileOutput).getStreamInfo());

        Path streamOutput = outputDir.resolve("java-output-stream.flac");
        try (OutputStream output = Files.newOutputStream(streamOutput)) {
            new FlacEncoder().encode(
                    output,
                    demoFormat,
                    demoSamples,
                    new FlacEncodingMetadata(Map.of("TITLE", List.of("jflac Java OutputStream demo")))
            );
        }
        try (InputStream encodedStream = Files.newInputStream(streamOutput)) {
            FlacDecodedAudio decodedStreamOutput = new FlacDecoder().decode(encodedStream);
            System.out.printf("OutputStream encode then InputStream decode: %,d frame(s)%n",
                    decodedStreamOutput.getTotalFrames());
        }

        Path channelOutput = outputDir.resolve("java-seekable-channel.flac");
        try (SeekableByteChannel channel = Files.newByteChannel(
                channelOutput,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        )) {
            new FlacEncoder().encode(
                    channel,
                    demoFormat,
                    demoSamples,
                    new FlacEncodingMetadata(Map.of("TITLE", List.of("jflac Java seekable channel demo")))
            );
        }
        printStreamInfo("encoded seekable channel", new FlacMetadataReader().read(channelOutput).getStreamInfo());

        Path editedCopy = outputDir.resolve("java-edited-copy.flac");
        Files.copy(input, editedCopy, StandardCopyOption.REPLACE_EXISTING);
        new FlacMetadataEditor().edit(editedCopy, session -> session.setVorbisComments(
                Map.of("TITLE", List.of("Edited by JavaFeatureDemo"))
        ));
        System.out.println("Edited copy comments: "
                + new FlacMetadataReader().read(editedCopy).getVorbisComment().getComments());

        System.out.println("Example output directory: " + outputDir);
    }

    private static long choosePreviewFrames(FlacStreamInfo streamInfo) {
        if (streamInfo.getTotalSamples() > 0L) {
            return Math.min(PREVIEW_FRAMES, streamInfo.getTotalSamples());
        }
        return PREVIEW_FRAMES;
    }

    private static void printStreamInfo(String label, FlacStreamInfo info) {
        System.out.printf(
                "%s STREAMINFO: %,d Hz, %d channel(s), %d bit, total samples %,d%n",
                label,
                info.getSampleRate(),
                info.getChannels(),
                info.getBitsPerSample(),
                info.getTotalSamples()
        );
    }

    private static int[] deterministicPcm(FlacAudioFormat format) {
        int[] samples = new int[Math.multiplyExact(JavaFeatureDemo.DEMO_FRAMES, format.getChannels())];
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
