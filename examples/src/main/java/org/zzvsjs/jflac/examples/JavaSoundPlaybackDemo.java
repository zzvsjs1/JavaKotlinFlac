package org.zzvsjs.jflac.examples;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class JavaSoundPlaybackDemo {
    private static final int BUFFER_BYTES = 16 * 1024;
    private static final float[] FALLBACK_SAMPLE_RATES = { 48_000f, 44_100f };
    private static final int[] FALLBACK_SAMPLE_SIZES_IN_BITS = { 32, 24, 16 };

    private JavaSoundPlaybackDemo() {
    }

    public static void main(String[] args) throws Exception {
        Path input = validateInputPath(args);

        System.out.println("Opening native FLAC through Java Sound: " + input);
        playEntireFile(input.toFile());
        System.out.println("Playback finished.");
    }

    static Path validateInputPath(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: JavaSoundPlaybackDemo <input.flac>");
        }

        Path input = Path.of(args[0]).normalize();
        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("Input FLAC file does not exist: " + input);
        }

        return input;
    }

    private static void playEntireFile(File input) throws Exception {
        try (AudioInputStream audioInput = AudioSystem.getAudioInputStream(input)) {
            AudioFormat decodedFormat = audioInput.getFormat();
            System.out.println("Decoded Java Sound format: " + describe(decodedFormat));

            AudioFormat playbackFormat = selectPlaybackFormat(decodedFormat, SystemPlaybackSupport.INSTANCE);
            AudioInputStream playbackInput = audioInput;
            if (!sameFormat(decodedFormat, playbackFormat)) {
                playbackInput = AudioSystem.getAudioInputStream(playbackFormat, audioInput);
                System.out.println("Converted playback format: " + describe(playbackFormat));
            }

            try {
                try (SourceDataLine line = openSourceLine(playbackFormat)) {
                    byte[] buffer = new byte[BUFFER_BYTES];
                    int read;

                    line.start();
                    while ((read = playbackInput.read(buffer, 0, buffer.length)) != -1) {
                        writeFully(line, buffer, read);
                    }

                    /*
                     * SourceDataLine.write only queues bytes into the mixer buffer.
                     * drain() waits until the final queued PCM frame reaches the
                     * selected output device, so the process does not exit before
                     * the tail of the music is actually heard.
                     */
                    line.drain();
                    line.stop();
                }
            } finally {
                if (playbackInput != audioInput) {
                    playbackInput.close();
                }
            }
        }
    }

    static AudioFormat selectPlaybackFormat(AudioFormat decodedFormat, PlaybackSupport playbackSupport)
            throws LineUnavailableException {
        for (AudioFormat candidate : playbackFormatCandidates(decodedFormat)) {
            boolean originalFormat = sameFormat(decodedFormat, candidate);
            if (playbackSupport.isLineSupported(candidate)
                    && (originalFormat || playbackSupport.isConversionSupported(candidate, decodedFormat))) {
                return candidate;
            }
        }

        throw new LineUnavailableException(
                "No output line supports decoded PCM format or the standard fallback formats: "
                        + describe(decodedFormat)
        );
    }

    private static List<AudioFormat> playbackFormatCandidates(AudioFormat decodedFormat) {
        List<AudioFormat> candidates = new ArrayList<>();
        candidates.add(decodedFormat);

        /*
         * Keep the sample rate before changing the sample size. Resampling is
         * the larger quality and compatibility step, so a device that can
         * accept the FLAC stream's rate with a different PCM width is preferred
         * over a lower common output rate.
         */
        addPcmCandidates(candidates, decodedFormat.getSampleRate(), decodedFormat.getChannels());
        for (float sampleRate : FALLBACK_SAMPLE_RATES) {
            addPcmCandidates(candidates, sampleRate, decodedFormat.getChannels());
        }

        return candidates;
    }

    private static void addPcmCandidates(List<AudioFormat> candidates, float sampleRate, int channels) {
        for (int bitsPerSample : FALLBACK_SAMPLE_SIZES_IN_BITS) {
            addIfAbsent(candidates, signedPcm(sampleRate, bitsPerSample, channels));
        }
    }

    private static void addIfAbsent(List<AudioFormat> candidates, AudioFormat candidate) {
        for (AudioFormat existing : candidates) {
            if (sameFormat(existing, candidate)) {
                return;
            }
        }

        candidates.add(candidate);
    }

    private static AudioFormat signedPcm(float sampleRate, int bitsPerSample, int channels) {
        int bytesPerSample = (bitsPerSample + 7) / 8;
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                bitsPerSample,
                channels,
                channels * bytesPerSample,
                sampleRate,
                false
        );
    }

    private static SourceDataLine openSourceLine(AudioFormat format) throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("No output line supports decoded PCM format: " + describe(format));
        }

        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format);
        return line;
    }

    interface PlaybackSupport {
        boolean isLineSupported(AudioFormat format);

        boolean isConversionSupported(AudioFormat target, AudioFormat source);
    }

    private enum SystemPlaybackSupport implements PlaybackSupport {
        INSTANCE;

        @Override
        public boolean isLineSupported(AudioFormat format) {
            return AudioSystem.isLineSupported(new DataLine.Info(SourceDataLine.class, format));
        }

        @Override
        public boolean isConversionSupported(AudioFormat target, AudioFormat source) {
            return AudioSystem.isConversionSupported(target, source);
        }
    }

    private static void writeFully(SourceDataLine line, byte[] buffer, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            int written = line.write(buffer, offset, length - offset);
            if (written <= 0) {
                throw new IOException("Audio output line stopped accepting PCM bytes.");
            }
            offset += written;
        }
    }

    private static boolean sameFormat(AudioFormat left, AudioFormat right) {
        return left.getEncoding().equals(right.getEncoding())
                && left.getSampleRate() == right.getSampleRate()
                && left.getSampleSizeInBits() == right.getSampleSizeInBits()
                && left.getChannels() == right.getChannels()
                && left.getFrameSize() == right.getFrameSize()
                && left.getFrameRate() == right.getFrameRate()
                && left.isBigEndian() == right.isBigEndian();
    }

    private static String describe(AudioFormat format) {
        return String.format(
                "%.0f Hz, %d channel(s), %d bit, frame size %d byte(s), %s-endian, %s",
                format.getSampleRate(),
                format.getChannels(),
                format.getSampleSizeInBits(),
                format.getFrameSize(),
                format.isBigEndian() ? "big" : "little",
                format.getEncoding()
        );
    }
}
