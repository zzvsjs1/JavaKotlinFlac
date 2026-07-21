package org.zzvsjs.jflac.sound;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.util.Objects;

/*
 * Converts Java Sound PCM bytes into the signed integer sample layout expected
 * by the core jflac encoder.
 *
 * Java Sound exposes several PCM shapes through one AudioFormat model. This
 * reader accepts only the layouts that can be converted without scaling:
 * signed 8/16/24/32-bit PCM and unsigned 8-bit PCM. Multibyte samples honor
 * the AudioFormat endian flag, then sign extension moves the value into the
 * equivalent signed Int range.
 */
final class PcmSampleReader {
    private static final int BUFFER_FRAMES = 1024;

    private final AudioInputStream input;
    private final AudioFormat format;
    private final AudioFormat.Encoding encoding;
    private final int sampleRate;
    private final int channels;
    private final int bitsPerSample;
    private final int bytesPerSample;
    private final int frameSize;
    private final byte[] frameBuffer;

    PcmSampleReader(AudioInputStream input) {
        this.input = Objects.requireNonNull(input, "input");
        this.format = input.getFormat();
        this.encoding = format.getEncoding();
        this.sampleRate = validatedSampleRate(format);
        this.channels = validatedChannels(format);
        this.bitsPerSample = validatedBitsPerSample(format);
        this.bytesPerSample = bitsPerSample / Byte.SIZE;
        this.frameSize = validatedFrameSize(format, bytesPerSample, channels);
        this.frameBuffer = new byte[Math.multiplyExact(BUFFER_FRAMES, frameSize)];
    }

    int readFrames(int[] output, int maxFrames) throws IOException {
        /*
         * Public encoder APIs count in frames, but the output array is
         * interleaved samples. For stereo, maxFrames=1024 needs 2048 Int
         * slots. Keeping this check here means caller mistakes fail before any
         * partially-filled buffer can reach libFLAC.
         */
        if (maxFrames < 0) {
            throw new IllegalArgumentException("maxFrames must not be negative");
        }

        Objects.requireNonNull(output, "output");
        long samplesWanted = (long) maxFrames * channels;
        if (output.length < samplesWanted) {
            throw new IllegalArgumentException("output is too small for the requested frame count");
        }

        if (maxFrames == 0) {
            return 0;
        }

        int bytesWanted = Math.min(maxFrames, BUFFER_FRAMES) * frameSize;
        int bytesRead = readBytesForFrames(bytesWanted);
        if (bytesRead == -1) {
            return -1;
        }

        if (bytesRead % frameSize != 0) {
            throw new IOException("Unexpected EOF in final partial PCM frame");
        }

        int framesRead = bytesRead / frameSize;
        decodeFrames(output, framesRead);
        return framesRead;
    }

    int sampleRate() {
        return sampleRate;
    }

    int channels() {
        return channels;
    }

    int bitsPerSample() {
        return bitsPerSample;
    }

    Long totalSamplesEstimate() {
        long frameLength = input.getFrameLength();
        if (frameLength == AudioSystem.NOT_SPECIFIED) {
            return null;
        }

        return frameLength;
    }

    AudioFormat format() {
        return format;
    }

    private int readBytesForFrames(int bytesWanted) throws IOException {
        int total = 0;
        while (total < bytesWanted) {
            int read = input.read(frameBuffer, total, bytesWanted - total);
            if (read == -1) {
                return total == 0 ? -1 : total;
            }

            if (read == 0) {
                /*
                 * AudioInputStream should either make progress or report EOF
                 * for a positive read request. Treat zero as an exceptional no
                 * progress state so the writer cannot spin forever.
                 */
                throw new IOException("PCM input returned no bytes for a positive read request.");
            }

            total += read;
        }

        return total;
    }

    private void decodeFrames(int[] output, int framesRead) {
        int outputIndex = 0;
        int byteIndex = 0;
        for (int frame = 0; frame < framesRead; frame++) {
            for (int channel = 0; channel < channels; channel++) {
                output[outputIndex++] = decodeSample(byteIndex);
                byteIndex += bytesPerSample;
            }
        }
    }

    private int decodeSample(int byteIndex) {
        if (encoding.equals(AudioFormat.Encoding.PCM_UNSIGNED)) {
            /*
             * The only unsigned format accepted by this reader is 8-bit PCM.
             * Java Sound stores it as 0..255; FLAC wants signed samples centred
             * on zero, so subtract the midpoint.
             */
            return (frameBuffer[byteIndex] & 0xff) - 128;
        }

        if (bitsPerSample == 8) {
            return frameBuffer[byteIndex];
        }

        int sample = 0;
        if (format.isBigEndian()) {
            for (int i = 0; i < bytesPerSample; i++) {
                sample = (sample << Byte.SIZE) | (frameBuffer[byteIndex + i] & 0xff);
            }
        } else {
            for (int i = bytesPerSample - 1; i >= 0; i--) {
                sample = (sample << Byte.SIZE) | (frameBuffer[byteIndex + i] & 0xff);
            }
        }

        int shift = Integer.SIZE - bitsPerSample;
        /*
         * Left shift moves the sign bit into Int's sign position; arithmetic
         * right shift then sign-extends back to the original bit depth.
         * Example for 24-bit -1: 0x00ffffff becomes 0xffffffff.
         */
        return (sample << shift) >> shift;
    }

    private static int validatedSampleRate(AudioFormat format) {
        float sampleRate = format.getSampleRate();
        if (!Float.isFinite(sampleRate) || sampleRate <= 0.0f || sampleRate > Integer.MAX_VALUE
                || sampleRate != Math.rint(sampleRate)) {
            throw new IllegalArgumentException("sample rate must be a positive whole number");
        }

        return Math.toIntExact((long) sampleRate);
    }

    private static int validatedChannels(AudioFormat format) {
        int channels = format.getChannels();
        if (channels < 1 || channels > 8) {
            throw new IllegalArgumentException("channel count must be between 1 and 8");
        }

        return channels;
    }

    private static int validatedBitsPerSample(AudioFormat format) {
        AudioFormat.Encoding encoding = format.getEncoding();
        int bits = format.getSampleSizeInBits();
        if (encoding.equals(AudioFormat.Encoding.PCM_UNSIGNED)) {
            if (bits == 8) {
                return bits;
            }

            throw new IllegalArgumentException("only 8-bit unsigned PCM is supported");
        }

        if (!encoding.equals(AudioFormat.Encoding.PCM_SIGNED)) {
            throw new IllegalArgumentException("only PCM signed and 8-bit PCM unsigned formats are supported");
        }

        if (bits == 8 || bits == 16 || bits == 24 || bits == 32) {
            return bits;
        }

        throw new IllegalArgumentException("signed PCM sample size must be 8, 16, 24, or 32 bits");
    }

    private static int validatedFrameSize(AudioFormat format, int bytesPerSample, int channels) {
        int expectedFrameSize = Math.multiplyExact(bytesPerSample, channels);
        int frameSize = format.getFrameSize();
        if (frameSize != expectedFrameSize) {
            throw new IllegalArgumentException("frame size does not match sample size and channel count");
        }

        return frameSize;
    }
}
