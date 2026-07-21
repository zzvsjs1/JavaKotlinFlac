package org.zzvsjs.jflac.examples.playback;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class PlaybackAudioOutputTest {
    private static final AudioFormat FORMAT = new AudioFormat(44_100f, 16, 2, true, false);

    @Test
    public void failedLineOpenStillClosesTheAcquiredMixerLine() {
        AtomicBoolean closed = new AtomicBoolean();
        SourceDataLine line = failingLine(closed, false);

        LineUnavailableException failure = assertThrows(
                LineUnavailableException.class,
                () -> PlaybackAudioOutput.openPlaybackLine(
                        FORMAT,
                        providerFor(line)
                )
        );

        assertEquals("test open failure", failure.getMessage());
        assertTrue(closed.get());
    }

    @Test
    public void failedLineCleanupIsSuppressedOnTheOpenFailure() {
        SourceDataLine line = failingLine(new AtomicBoolean(), true);

        LineUnavailableException failure = assertThrows(
                LineUnavailableException.class,
                () -> PlaybackAudioOutput.openPlaybackLine(
                        FORMAT,
                        providerFor(line)
                )
        );

        assertEquals(1, failure.getSuppressed().length);
        assertEquals("test close failure", failure.getSuppressed()[0].getMessage());
    }

    @Test
    public void supportedDecodedFormatIsKeptWithoutConversion() throws Exception {
        AudioFormat decoded = pcm(44_100f, 16, 2);

        AudioFormat selected = PlaybackAudioOutput.selectPlaybackFormat(
                decoded,
                new PlaybackAudioOutput.PlaybackSupport() {
                    @Override
                    public boolean isLineSupported(AudioFormat format) {
                        return format.equals(decoded);
                    }

                    @Override
                    public boolean isConversionSupported(
                            AudioFormat target,
                            AudioFormat source
                    ) {
                        return false;
                    }
                }
        );

        assertEquals(decoded, selected);
    }

    @Test
    public void unsupportedDecodedFormatFallsBackToSixteenBitPcm() throws Exception {
        AudioFormat decoded = pcm(96_000f, 24, 2);

        AudioFormat selected = PlaybackAudioOutput.selectPlaybackFormat(
                decoded,
                supportFor(decoded, 48_000f, 16, 2)
        );

        assertPcmFormat(selected, 48_000f, 16, 2);
    }

    @Test
    public void unsupportedDecodedFormatFallsBackToThirtyTwoBitPcm() throws Exception {
        AudioFormat decoded = pcm(96_000f, 24, 2);

        AudioFormat selected = PlaybackAudioOutput.selectPlaybackFormat(
                decoded,
                supportFor(decoded, 48_000f, 32, 2)
        );

        assertPcmFormat(selected, 48_000f, 32, 2);
    }

    @Test
    public void unsupportedDecodedFormatFallsBackToTwentyFourBitPcm() throws Exception {
        AudioFormat decoded = pcm(96_000f, 32, 2);

        AudioFormat selected = PlaybackAudioOutput.selectPlaybackFormat(
                decoded,
                supportFor(decoded, 44_100f, 24, 2)
        );

        assertPcmFormat(selected, 44_100f, 24, 2);
    }

    @Test
    public void unsupportedOutputReportsTheRejectedDecodedFormat() {
        AudioFormat decoded = pcm(96_000f, 24, 2);

        LineUnavailableException error = assertThrows(
                LineUnavailableException.class,
                () -> PlaybackAudioOutput.selectPlaybackFormat(
                        decoded,
                        new PlaybackAudioOutput.PlaybackSupport() {
                            @Override
                            public boolean isLineSupported(AudioFormat format) {
                                return false;
                            }

                            @Override
                            public boolean isConversionSupported(
                                    AudioFormat target,
                                    AudioFormat source
                            ) {
                                return false;
                            }
                        }
                )
        );

        assertEquals(
                "No output line supports decoded PCM format or the standard fallback formats: "
                        + "96000 Hz, 2 channel(s), 24 bit, frame size 6 byte(s), "
                        + "little-endian, PCM_SIGNED",
                error.getMessage()
        );
    }

    private static PlaybackAudioOutput.PlaybackSupport supportFor(
            AudioFormat decoded,
            float sampleRate,
            int bitsPerSample,
            int channels
    ) {
        return new PlaybackAudioOutput.PlaybackSupport() {
            @Override
            public boolean isLineSupported(AudioFormat format) {
                return format.getSampleRate() == sampleRate
                        && format.getSampleSizeInBits() == bitsPerSample
                        && format.getChannels() == channels;
            }

            @Override
            public boolean isConversionSupported(AudioFormat target, AudioFormat source) {
                return source.equals(decoded)
                        && target.getSampleRate() == sampleRate
                        && target.getSampleSizeInBits() == bitsPerSample
                        && target.getChannels() == channels;
            }
        };
    }

    private static AudioFormat pcm(float sampleRate, int bitsPerSample, int channels) {
        int bytesPerSample = (bitsPerSample + 7) / 8;
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                bitsPerSample,
                channels,
                bytesPerSample * channels,
                sampleRate,
                false
        );
    }

    private static void assertPcmFormat(
            AudioFormat format,
            float sampleRate,
            int bitsPerSample,
            int channels
    ) {
        assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding());
        assertEquals(sampleRate, format.getSampleRate());
        assertEquals(bitsPerSample, format.getSampleSizeInBits());
        assertEquals(channels, format.getChannels());
        assertEquals(((bitsPerSample + 7) / 8) * channels, format.getFrameSize());
        assertEquals(sampleRate, format.getFrameRate());
        assertEquals(false, format.isBigEndian());
    }

    private static PlaybackAudioOutput.LineProvider providerFor(SourceDataLine line) {
        return new PlaybackAudioOutput.LineProvider() {
            @Override
            public boolean isLineSupported(DataLine.Info info) {
                return true;
            }

            @Override
            public SourceDataLine getLine(DataLine.Info info) {
                return line;
            }
        };
    }

    private static SourceDataLine failingLine(
            AtomicBoolean closed,
            boolean closeFails
    ) {
        return (SourceDataLine) Proxy.newProxyInstance(
                PlaybackAudioOutputTest.class.getClassLoader(),
                new Class<?>[] { SourceDataLine.class },
                (proxy, method, arguments) -> {
                    if ("open".equals(method.getName())) {
                        throw new LineUnavailableException("test open failure");
                    }

                    if ("close".equals(method.getName())) {
                        closed.set(true);
                        if (closeFails) {
                            throw new IllegalStateException("test close failure");
                        }

                        return null;
                    }

                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }

        if (type == boolean.class) {
            return false;
        }

        if (type == char.class) {
            return '\0';
        }

        if (type == byte.class) {
            return (byte) 0;
        }

        if (type == short.class) {
            return (short) 0;
        }

        if (type == int.class) {
            return 0;
        }

        if (type == long.class) {
            return 0L;
        }

        if (type == float.class) {
            return 0f;
        }

        return 0d;
    }
}
