package org.zzvsjs.jflac.sound;

import org.zzvsjs.jflac.FlacPullDecodingSession;
import org.zzvsjs.jflac.FlacStreamInfo;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

final class FlacPcmInputStream extends InputStream {
    private static final int DEFAULT_CHUNK_FRAMES = 1024;

    private final FlacPullDecodingSession session;
    private final InputStream sourceToClose;
    private final int channels;
    private final int bitsPerSample;
    private final int bytesPerSample;
    private final int[] sampleBuffer;
    private final byte[] byteBuffer;

    private int bytePosition;
    private int byteLimit;
    private boolean eof;
    private boolean closed;

    FlacPcmInputStream(FlacPullDecodingSession session) {
        this(session, null);
    }

    FlacPcmInputStream(FlacPullDecodingSession session, InputStream sourceToClose) {
        this.session = Objects.requireNonNull(session, "session");
        this.sourceToClose = sourceToClose;

        FlacStreamInfo info = session.getStreamInfo();
        this.channels = info.getChannels();
        this.bitsPerSample = info.getBitsPerSample();
        this.bytesPerSample = (bitsPerSample + 7) / 8;

        int chunkSamples = Math.multiplyExact(DEFAULT_CHUNK_FRAMES, channels);
        this.sampleBuffer = new int[chunkSamples];
        this.byteBuffer = new byte[Math.multiplyExact(chunkSamples, bytesPerSample)];
    }

    @Override
    public int read() throws IOException {
        byte[] oneByte = new byte[1];
        int read = read(oneByte, 0, 1);
        return read == -1 ? -1 : oneByte[0] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        ensureOpen();
        Objects.requireNonNull(buffer, "buffer");
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) {
            return 0;
        }

        int copied = 0;
        while (copied < length) {
            if (bytePosition == byteLimit && !fillByteBuffer()) {
                break;
            }

            int bytesToCopy = Math.min(length - copied, byteLimit - bytePosition);
            System.arraycopy(byteBuffer, bytePosition, buffer, offset + copied, bytesToCopy);
            bytePosition += bytesToCopy;
            copied += bytesToCopy;
        }

        return copied == 0 ? -1 : copied;
    }

    private boolean fillByteBuffer() throws IOException {
        if (eof) {
            return false;
        }

        int frames;
        try {
            frames = session.readInterleaved(sampleBuffer, DEFAULT_CHUNK_FRAMES);
        } catch (RuntimeException e) {
            throw new IOException("Failed to decode FLAC PCM.", e);
        }

        if (frames == -1) {
            eof = true;
            return false;
        }

        if (frames == 0) {
            throw new IOException("Native FLAC decoder returned no PCM frames for a positive read request.");
        }

        int samples = Math.multiplyExact(frames, channels);
        bytePosition = 0;
        byteLimit = Math.multiplyExact(samples, bytesPerSample);
        int outputOffset = 0;
        for (int sampleIndex = 0; sampleIndex < samples; sampleIndex++) {
            writeLittleEndianSample(sampleBuffer[sampleIndex], bitsPerSample, byteBuffer, outputOffset);
            outputOffset += bytesPerSample;
        }
        return true;
    }

    /*
     * jflac exposes decoded PCM as signed integer samples. Java Sound receives
     * the PCM byte order declared by FlacAudioFormats, so this adapter writes the
     * low-order bytes first for little-endian PCM_SIGNED output.
     */
    private static void writeLittleEndianSample(int sample, int bitsPerSample, byte[] output, int offset) {
        int bytesPerSample = (bitsPerSample + 7) / 8;
        for (int byteIndex = 0; byteIndex < bytesPerSample; byteIndex++) {
            output[offset + byteIndex] = (byte) (sample >> (byteIndex * Byte.SIZE));
        }
    }

    static byte[] encodeSampleForTest(int sample, int bitsPerSample) {
        byte[] bytes = new byte[(bitsPerSample + 7) / 8];
        writeLittleEndianSample(sample, bitsPerSample, bytes, 0);
        return bytes;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("The FLAC PCM stream is closed.");
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;

        IOException failure = null;
        try {
            session.close();
        } catch (RuntimeException e) {
            failure = new IOException("Failed to close FLAC PCM decoder.", e);
        } finally {
            if (sourceToClose != null) {
                try {
                    sourceToClose.close();
                } catch (IOException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
