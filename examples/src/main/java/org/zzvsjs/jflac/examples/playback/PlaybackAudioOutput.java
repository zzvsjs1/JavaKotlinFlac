package org.zzvsjs.jflac.examples.playback;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.IOError;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Java Sound output-line negotiation shared by playback sessions and views. */
final class PlaybackAudioOutput {
    private static final float[] FALLBACK_SAMPLE_RATES = { 48_000f, 44_100f };
    private static final int[] FALLBACK_SAMPLE_SIZES_IN_BITS = { 32, 24, 16 };

    private PlaybackAudioOutput() {
    }

    static AudioFormat selectPlaybackFormat(
            AudioFormat decodedFormat,
            PlaybackSupport playbackSupport
    ) throws LineUnavailableException {
        for (AudioFormat candidate : playbackFormatCandidates(decodedFormat)) {
            boolean originalFormat = isSamePcmFormat(decodedFormat, candidate);
            if (playbackSupport.isLineSupported(candidate)
                    && (originalFormat
                    || playbackSupport.isConversionSupported(candidate, decodedFormat))) {
                return candidate;
            }
        }

        throw new LineUnavailableException(
                "No output line supports decoded PCM format or the standard fallback formats: "
                        + describeAudioFormat(decodedFormat)
        );
    }

    private static List<AudioFormat> playbackFormatCandidates(AudioFormat decodedFormat) {
        List<AudioFormat> candidates = new ArrayList<>();
        candidates.add(decodedFormat);

        /*
         * Keep the source sample rate before changing it. Resampling is the
         * larger quality change, so only try common fallback rates after the
         * mixer has rejected every supported sample size at the source rate.
         */
        addPcmCandidates(candidates, decodedFormat.getSampleRate(), decodedFormat.getChannels());
        for (float sampleRate : FALLBACK_SAMPLE_RATES) {
            addPcmCandidates(candidates, sampleRate, decodedFormat.getChannels());
        }

        return candidates;
    }

    private static void addPcmCandidates(
            List<AudioFormat> candidates,
            float sampleRate,
            int channels
    ) {
        for (int bitsPerSample : FALLBACK_SAMPLE_SIZES_IN_BITS) {
            addIfAbsent(candidates, signedPcm(sampleRate, bitsPerSample, channels));
        }
    }

    private static void addIfAbsent(List<AudioFormat> candidates, AudioFormat candidate) {
        for (AudioFormat existing : candidates) {
            if (isSamePcmFormat(existing, candidate)) {
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

    static SourceDataLine openPlaybackLine(AudioFormat format) throws LineUnavailableException {
        return openPlaybackLine(format, SystemLineProvider.INSTANCE);
    }

    static SourceDataLine openPlaybackLine(
            AudioFormat format,
            LineProvider lineProvider
    ) throws LineUnavailableException {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(lineProvider, "lineProvider");
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        if (!lineProvider.isLineSupported(info)) {
            throw new LineUnavailableException(
                    "No output line supports decoded PCM format: " + describeAudioFormat(format)
            );
        }

        SourceDataLine line = Objects.requireNonNull(
                lineProvider.getLine(info),
                "lineProvider returned null"
        );
        try {
            line.open(format);
            return line;
        } catch (LineUnavailableException
                 | RuntimeException
                 | IOError
                 | LinkageError failure) {
            closeAfterOpenFailure(line, failure);
            throw failure;
        }
    }

    private static void closeAfterOpenFailure(SourceDataLine line, Throwable failure) {
        try {
            line.close();
        } catch (RuntimeException | IOError | LinkageError closeFailure) {
            if (closeFailure != failure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    static boolean isSamePcmFormat(AudioFormat left, AudioFormat right) {
        return left.getEncoding().equals(right.getEncoding())
                && Float.compare(left.getSampleRate(), right.getSampleRate()) == 0
                && left.getSampleSizeInBits() == right.getSampleSizeInBits()
                && left.getChannels() == right.getChannels()
                && left.getFrameSize() == right.getFrameSize()
                && Float.compare(left.getFrameRate(), right.getFrameRate()) == 0
                && left.isBigEndian() == right.isBigEndian();
    }

    static String describeAudioFormat(AudioFormat format) {
        return String.format(
                Locale.ROOT,
                "%.0f Hz, %d channel(s), %d bit, frame size %d byte(s), %s-endian, %s",
                format.getSampleRate(),
                format.getChannels(),
                format.getSampleSizeInBits(),
                format.getFrameSize(),
                format.isBigEndian() ? "big" : "little",
                format.getEncoding()
        );
    }

    interface PlaybackSupport {
        boolean isLineSupported(AudioFormat format);

        boolean isConversionSupported(AudioFormat target, AudioFormat source);
    }

    interface LineProvider {
        boolean isLineSupported(DataLine.Info info);

        SourceDataLine getLine(DataLine.Info info) throws LineUnavailableException;
    }

    private enum SystemLineProvider implements LineProvider {
        INSTANCE;

        @Override
        public boolean isLineSupported(DataLine.Info info) {
            return AudioSystem.isLineSupported(info);
        }

        @Override
        public SourceDataLine getLine(DataLine.Info info) throws LineUnavailableException {
            return (SourceDataLine) AudioSystem.getLine(info);
        }
    }

    enum SystemPlaybackSupport implements PlaybackSupport {
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
}
