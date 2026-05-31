package org.zzvsjs.jflac.sound;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class PcmSampleReaderTest {
    @Test
    public void readsLittleEndianSignedSixteenBitSamples() throws Exception {
        byte[] bytes = new byte[] { 0x34, 0x12, (byte) 0xfe, (byte) 0xff };
        PcmSampleReader reader = reader(bytes, signed(16, 1, false), 2);
        int[] samples = new int[2];

        assertEquals(2, reader.readFrames(samples, 2));
        assertArrayEquals(new int[] { 0x1234, -2 }, samples);
        assertEquals(-1, reader.readFrames(samples, 2));
    }

    @Test
    public void readsBigEndianSignedTwentyFourBitSamples() throws Exception {
        byte[] bytes = new byte[] { 0x00, 0x12, 0x34, (byte) 0xff, (byte) 0xff, (byte) 0xfe };
        PcmSampleReader reader = reader(bytes, signed(24, 1, true), 2);
        int[] samples = new int[2];

        assertEquals(2, reader.readFrames(samples, 2));
        assertArrayEquals(new int[] { 0x1234, -2 }, samples);
    }

    @Test
    public void convertsUnsignedEightBitToSignedCentre() throws Exception {
        byte[] bytes = new byte[] { 0, (byte) 128, (byte) 255 };
        PcmSampleReader reader = reader(bytes, unsigned8(1), 3);
        int[] samples = new int[3];

        assertEquals(3, reader.readFrames(samples, 3));
        assertArrayEquals(new int[] { -128, 0, 127 }, samples);
    }

    @Test
    public void rejectsFinalPartialFrame() {
        AudioInputStream input = new AudioInputStream(
                new ByteArrayInputStream(new byte[] { 0x01 }),
                signed(16, 1, false),
                AudioSystem.NOT_SPECIFIED
        );
        PcmSampleReader reader = new PcmSampleReader(input);
        int[] samples = new int[1];

        assertThrows(java.io.IOException.class, () -> reader.readFrames(samples, 1));
    }

    @Test
    public void rejectsUnsupportedUnsignedSixteenBitFormat() {
        AudioInputStream input = new AudioInputStream(
                new ByteArrayInputStream(new byte[] { 0, 0 }),
                new AudioFormat(AudioFormat.Encoding.PCM_UNSIGNED, 44_100f, 16, 1, 2, 44_100f, false),
                1
        );

        assertThrows(IllegalArgumentException.class, () -> new PcmSampleReader(input));
    }

    @Test
    public void totalSamplesEstimateUsesJavaSoundFrameLength() {
        PcmSampleReader reader = reader(new byte[400], signed(16, 2, false), 100);

        assertEquals(Long.valueOf(100L), reader.totalSamplesEstimate());
    }

    @Test
    public void zeroByteReadForPositiveRequestFails() {
        AudioInputStream input = new AudioInputStream(
                new ZeroReadInputStream(),
                signed(16, 1, false),
                AudioSystem.NOT_SPECIFIED
        );
        PcmSampleReader reader = new PcmSampleReader(input);
        int[] samples = new int[1];

        assertThrows(java.io.IOException.class, () -> reader.readFrames(samples, 1));
    }

    @Test
    public void rejectsChannelCountAboveFlacLimit() {
        AudioInputStream input = new AudioInputStream(
                new ByteArrayInputStream(new byte[18]),
                signed(16, 9, false),
                1
        );

        assertThrows(IllegalArgumentException.class, () -> new PcmSampleReader(input));
    }

    private static PcmSampleReader reader(byte[] bytes, AudioFormat format, long frames) {
        return new PcmSampleReader(new AudioInputStream(new ByteArrayInputStream(bytes), format, frames));
    }

    private static AudioFormat signed(int bits, int channels, boolean bigEndian) {
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44_100f, bits, channels, (bits / 8) * channels, 44_100f, bigEndian);
    }

    private static AudioFormat unsigned8(int channels) {
        return new AudioFormat(AudioFormat.Encoding.PCM_UNSIGNED, 44_100f, 8, channels, channels, 44_100f, false);
    }

    private static final class ZeroReadInputStream extends java.io.InputStream {
        @Override
        public int read() {
            return 0;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return 0;
        }
    }
}
