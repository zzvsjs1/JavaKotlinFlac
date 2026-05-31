package org.zzvsjs.jflac.examples;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class JavaSoundPlaybackDemoTest {
    @Test
    public void validateInputPathRequiresOneArgument() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> JavaSoundPlaybackDemo.validateInputPath(new String[0])
        );

        assertEquals("Usage: JavaSoundPlaybackDemo <input.flac>", error.getMessage());
    }

    @Test
    public void validateInputPathRequiresRegularFile() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> JavaSoundPlaybackDemo.validateInputPath(new String[] { "missing.flac" })
        );

        assertEquals("Input FLAC file does not exist: missing.flac", error.getMessage());
    }

    @Test
    public void validateInputPathAcceptsExistingFile() throws Exception {
        Path file = Files.createTempFile("jflac-java-sound-playback", ".flac");
        try {
            assertEquals(file.normalize(), JavaSoundPlaybackDemo.validateInputPath(new String[] { file.toString() }));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void selectPlaybackFormatKeepsSupportedDecodedFormat() throws Exception {
        AudioFormat decoded = pcm(44_100f, 16, 2);

        AudioFormat selected = JavaSoundPlaybackDemo.selectPlaybackFormat(
                decoded,
                new JavaSoundPlaybackDemo.PlaybackSupport() {
                    @Override
                    public boolean isLineSupported(AudioFormat format) {
                        return format.equals(decoded);
                    }

                    @Override
                    public boolean isConversionSupported(AudioFormat target, AudioFormat source) {
                        return false;
                    }
                }
        );

        assertEquals(decoded, selected);
    }

    @Test
    public void selectPlaybackFormatFallsBackToConvertedSixteenBitPcm() throws Exception {
        AudioFormat decoded = pcm(96_000f, 24, 2);

        AudioFormat selected = JavaSoundPlaybackDemo.selectPlaybackFormat(
                decoded,
                new JavaSoundPlaybackDemo.PlaybackSupport() {
                    @Override
                    public boolean isLineSupported(AudioFormat format) {
                        return format.getSampleSizeInBits() == 16
                                && format.getChannels() == 2
                                && format.getSampleRate() == 48_000f;
                    }

                    @Override
                    public boolean isConversionSupported(AudioFormat target, AudioFormat source) {
                        return target.getSampleSizeInBits() == 16 && source.equals(decoded);
                    }
                }
        );

        assertPcmFormat(selected, 48_000f, 16, 2);
    }

    @Test
    public void selectPlaybackFormatFallsBackToConvertedThirtyTwoBitPcm() throws Exception {
        AudioFormat decoded = pcm(96_000f, 24, 2);

        AudioFormat selected = JavaSoundPlaybackDemo.selectPlaybackFormat(
                decoded,
                new JavaSoundPlaybackDemo.PlaybackSupport() {
                    @Override
                    public boolean isLineSupported(AudioFormat format) {
                        return format.getSampleSizeInBits() == 32
                                && format.getChannels() == 2
                                && format.getSampleRate() == 48_000f;
                    }

                    @Override
                    public boolean isConversionSupported(AudioFormat target, AudioFormat source) {
                        return target.getSampleSizeInBits() == 32 && source.equals(decoded);
                    }
                }
        );

        assertPcmFormat(selected, 48_000f, 32, 2);
    }

    @Test
    public void selectPlaybackFormatFallsBackToConvertedTwentyFourBitPcm() throws Exception {
        AudioFormat decoded = pcm(96_000f, 32, 2);

        AudioFormat selected = JavaSoundPlaybackDemo.selectPlaybackFormat(
                decoded,
                new JavaSoundPlaybackDemo.PlaybackSupport() {
                    @Override
                    public boolean isLineSupported(AudioFormat format) {
                        return format.getSampleSizeInBits() == 24
                                && format.getChannels() == 2
                                && format.getSampleRate() == 44_100f;
                    }

                    @Override
                    public boolean isConversionSupported(AudioFormat target, AudioFormat source) {
                        return target.getSampleSizeInBits() == 24 && source.equals(decoded);
                    }
                }
        );

        assertPcmFormat(selected, 44_100f, 24, 2);
    }

    @Test
    public void selectPlaybackFormatReportsUnsupportedOutput() {
        AudioFormat decoded = pcm(96_000f, 24, 2);

        LineUnavailableException error = assertThrows(
                LineUnavailableException.class,
                () -> JavaSoundPlaybackDemo.selectPlaybackFormat(
                        decoded,
                        new JavaSoundPlaybackDemo.PlaybackSupport() {
                            @Override
                            public boolean isLineSupported(AudioFormat format) {
                                return false;
                            }

                            @Override
                            public boolean isConversionSupported(AudioFormat target, AudioFormat source) {
                                return false;
                            }
                        }
                )
        );

        assertEquals("No output line supports decoded PCM format or the standard fallback formats: "
                + "96000 Hz, 2 channel(s), 24 bit, frame size 6 byte(s), little-endian, PCM_SIGNED", error.getMessage());
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
}
