package org.zzvsjs.jflac.sound;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class FlacFormatProbeTest {
    private static final int OGG_BUDGET = 1 << 20;
    private static final int PADDING_BYTES = 12 * 1024 * 1024;
    private static final String OGG_FLAC = ""
            + "T2dnUwACAAAAAAAAAACAGnKDAAAAAOUMOC4BM39GTEFDAQAAAWZMYUMAAAAiAkACQAAAAAAElQH0APAA\n"
            + "AAAAAAAAAAAAAAAAAAAAAAAAAE9nZ1MAAAAAAAAAAAAAgBpygwEAAABaeJ3IAVCEAABMDAAAAExhdmY2\n"
            + "Mi4zLjEwMAIAAAAaAAAAZW5jb2Rlcj1MYXZjNjIuMTEuMTAwIGZsYWMWAAAAdGl0bGU9T2dnIEZMQUMg\n"
            + "Rml4dHVyZU9nZ1MABFAAAAAAAAAAgBpygwIAAAD8m/WsAWL/+GQIAE8JTgAABWsKMg3FD7YPzQ4FCpTl\n"
            + "mJO/JGtbXhj3jqRBkn77gI6TffVzFBQs4acetz6Lr5N1u+UFHChZ57mLqrkyL7PZ4UIEDDxBDFUVyrJl\n"
            + "NWNFmChQkQ8lnYAQIg==\n";

    @Test
    public void nativeProbeReadsOnlyStreamInfoBeforeLargePadding() throws Exception {
        GeneratedStream stream = largeNativeStream();

        AudioFileFormat format = new FlacAudioFileReader().getAudioFileFormat(stream);

        assertNativeFormat(format);
        assertTrue(stream.highWater <= 42, "The native probe must not read subsequent metadata.");
        assertTrue(stream.largestMark <= 42, "Native format probing must retain only a bounded prefix.");
        assertEquals(0, stream.position);
        assertFalse(stream.closed, "The caller owns the source.");
        assertArrayEquals(new byte[] { 'f', 'L', 'a', 'C' }, stream.readNBytes(4));
    }

    @Test
    public void nativeUrlProbeReadsBoundedPrefixAndClosesOwnedSource() throws Exception {
        GeneratedStream stream = largeNativeStream();

        AudioFileFormat format = new FlacAudioFileReader().getAudioFileFormat(urlFor(stream));

        assertNativeFormat(format);
        assertTrue(stream.highWater <= 8192, "A URL probe may prefetch one default buffer, not metadata megabytes.");
        assertTrue(stream.closed);
    }

    @Test
    public void nativeProbeRejectsTruncatedOrInvalidStreamInfoAndRestoresPosition() {
        byte[] prefix = nativePrefix();
        byte[] invalidType = prefix.clone();
        invalidType[4] = 1;
        byte[] invalidLength = prefix.clone();
        invalidLength[7] = 33;
        for (byte[] input : new byte[][] { Arrays.copyOf(prefix, 3), Arrays.copyOf(prefix, 41),
                invalidType, invalidLength }) {
            GeneratedStream stream = new GeneratedStream(input, input.length);
            assertThrows(UnsupportedAudioFileException.class,
                    () -> new FlacAudioFileReader().getAudioFileFormat(stream));
            assertEquals(0, stream.position);
            assertFalse(stream.closed);
        }
    }

    @Test
    public void nativeProbeRejectsZeroSampleRateAndRestoresPosition() {
        byte[] prefix = nativePrefix();
        Arrays.fill(prefix, 18, 21, (byte) 0);
        GeneratedStream stream = new GeneratedStream(prefix, prefix.length);

        assertThrows(UnsupportedAudioFileException.class,
                () -> new FlacAudioFileReader().getAudioFileFormat(stream));
        assertEquals(0, stream.position);
        assertFalse(stream.closed);
    }

    @Test
    public void nativeProbeDefersLaterMetadataValidation() throws Exception {
        // STREAMINFO is valid, but its following metadata block is missing.
        GeneratedStream stream = new GeneratedStream(nativePrefix(), 42);

        assertNativeFormat(new FlacAudioFileReader().getAudioFileFormat(stream));
        assertEquals(0, stream.position);
    }

    @Test
    public void formatProbeRequiresMarkResetBeforeReading() {
        GeneratedStream stream = new GeneratedStream(nativePrefix(), 42) {
            @Override
            public boolean markSupported() {
                return false;
            }
        };

        assertThrows(IOException.class, () -> new FlacAudioFileReader().getAudioFileFormat(stream));
        assertEquals(0, stream.highWater);
        assertFalse(stream.closed);
    }

    @Test
    public void oggProbeRestoresPositionAndKeepsCallerSourceOpen() throws Exception {
        byte[] bytes = Base64.getMimeDecoder().decode(OGG_FLAC);
        GeneratedStream stream = new GeneratedStream(bytes, bytes.length);

        AudioFileFormat format = new FlacAudioFileReader().getAudioFileFormat(stream);

        assertEquals(JflacAudioFileTypes.OGG_FLAC, format.getType());
        assertEquals("ogg", format.properties().get(JflacAudioFileProperties.CONTAINER));
        assertEquals(0, stream.position);
        assertFalse(stream.closed);
        assertArrayEquals(new byte[] { 'O', 'g', 'g', 'S' }, stream.readNBytes(4));
    }

    @Test
    public void oggUrlProbeClosesOwnedSource() throws Exception {
        byte[] bytes = Base64.getMimeDecoder().decode(OGG_FLAC);
        GeneratedStream stream = new GeneratedStream(bytes, bytes.length);

        AudioFileFormat format = new FlacAudioFileReader().getAudioFileFormat(urlFor(stream));

        assertEquals(JflacAudioFileTypes.OGG_FLAC, format.getType());
        assertTrue(stream.closed);
    }

    @Test
    public void corruptOggProbeIsUnsupportedAndRestoresPosition() {
        byte[] bytes = Base64.getMimeDecoder().decode(OGG_FLAC);
        bytes[22] ^= 1; // Damage the first page checksum; do not bypass libogg validation.
        GeneratedStream stream = new GeneratedStream(bytes, bytes.length);

        assertThrows(UnsupportedAudioFileException.class,
                () -> new FlacAudioFileReader().getAudioFileFormat(stream));
        assertEquals(0, stream.position);
        assertFalse(stream.closed);
    }

    @Test
    public void oggCandidateExhaustsBudgetWithoutReadingPastLimitAndRestoresPosition() {
        // A long unsynchronised candidate makes libogg search for a valid page.
        GeneratedStream stream = new GeneratedStream(new byte[] { 'O', 'g', 'g', 'S' }, 2 * OGG_BUDGET);

        assertThrows(UnsupportedAudioFileException.class,
                () -> new FlacAudioFileReader().getAudioFileFormat(stream));
        assertTrue(stream.highWater <= OGG_BUDGET, "No read may consume the byte after the Ogg probe budget.");
        assertTrue(stream.largestMark <= OGG_BUDGET, "The restore buffer must also be bounded.");
        assertEquals(0, stream.position);
        assertFalse(stream.closed);
    }

    @Test
    public void oggUrlCandidateExhaustsBudgetAndClosesSource() throws Exception {
        GeneratedStream stream = new GeneratedStream(new byte[] { 'O', 'g', 'g', 'S' }, 2 * OGG_BUDGET);

        assertThrows(UnsupportedAudioFileException.class,
                () -> new FlacAudioFileReader().getAudioFileFormat(urlFor(stream)));
        assertTrue(stream.highWater <= OGG_BUDGET, "URL buffering must not read beyond the Ogg budget.");
        assertTrue(stream.closed);
    }

    @Test
    public void probePreservesOriginalFailureWhenReadAndResetThrowSameException() {
        IOException failure = new IOException("source read and reset failed");
        boolean[] failedRead = { false };
        GeneratedStream stream = new GeneratedStream(new byte[] { 'O', 'g', 'g', 'S' }, 64) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                if (position >= 4) {
                    failedRead[0] = true;
                    throw failure;
                }
                return super.read(bytes, offset, Math.min(length, 4 - position));
            }

            @Override
            public void reset() throws IOException {
                if (failedRead[0]) {
                    throw failure;
                }
                super.reset();
            }
        };

        IOException actual = assertThrows(IOException.class,
                () -> new FlacAudioFileReader().getAudioFileFormat(stream));
        assertSame(failure, actual);
        assertEquals(0, actual.getSuppressed().length);
        assertFalse(stream.closed);
    }

    @Test
    public void probePreservesOriginalReadFailureWhenResetAlsoFails() {
        IOException readFailure = new IOException("source read failed");
        IOException resetFailure = new IOException("source reset failed");
        boolean[] failedRead = { false };
        GeneratedStream stream = new GeneratedStream(new byte[] { 'O', 'g', 'g', 'S' }, 64) {
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                if (position >= 4) {
                    failedRead[0] = true;
                    throw readFailure;
                }
                return super.read(bytes, offset, Math.min(length, 4 - position));
            }

            @Override
            public void reset() throws IOException {
                if (failedRead[0]) {
                    throw resetFailure;
                }
                super.reset();
            }
        };

        IOException actual = assertThrows(IOException.class,
                () -> new FlacAudioFileReader().getAudioFileFormat(stream));
        assertSame(readFailure, actual);
        assertArrayEquals(new Throwable[] { resetFailure }, actual.getSuppressed());
        assertFalse(stream.closed);
    }

    private static void assertNativeFormat(AudioFileFormat format) {
        assertEquals(JflacAudioFileTypes.FLAC, format.getType());
        assertEquals(44_100f, format.getFormat().getSampleRate());
        assertEquals(2, format.getFormat().getChannels());
        assertEquals(16, format.getFormat().getSampleSizeInBits());
        assertEquals(12, format.getFrameLength());
    }

    private static byte[] nativePrefix() {
        byte[] bytes = new byte[42];
        bytes[0] = 'f';
        bytes[1] = 'L';
        bytes[2] = 'a';
        bytes[3] = 'C';
        bytes[7] = 34;
        bytes[8] = 0x10;
        bytes[10] = 0x10; // Minimum and maximum block sizes: 4096 samples.
        long fields = (44_100L << 44) | (1L << 41) | (15L << 36) | 12;
        for (int i = 0; i < 8; i++) {
            bytes[18 + i] = (byte) (fields >>> (56 - i * 8));
        }
        return bytes;
    }

    private static GeneratedStream largeNativeStream() {
        return new GeneratedStream(nativePrefix(), 42 + 8 + 2 * PADDING_BYTES) {
            @Override
            protected int byteAt(int offset) {
                if (offset >= 42 && offset < 46) {
                    return new int[] { 1, 0xc0, 0, 0 }[offset - 42];
                }
                int finalHeader = 46 + PADDING_BYTES;
                if (offset >= finalHeader && offset < finalHeader + 4) {
                    return new int[] { 0x81, 0xc0, 0, 0 }[offset - finalHeader];
                }
                return super.byteAt(offset);
            }
        };
    }

    @SuppressWarnings("deprecation")
    private static URL urlFor(InputStream stream) throws Exception {
        return new URL(null, "jflac-probe-test://source", new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) {
                return new URLConnection(url) {
                    @Override
                    public void connect() {
                        connected = true;
                    }

                    @Override
                    public InputStream getInputStream() {
                        return stream;
                    }
                };
            }
        });
    }

    /** Generates zero-filled payloads without allocating metadata-sized arrays. */
    private static class GeneratedStream extends InputStream {
        private final byte[] prefix;
        private final int size;
        protected int position;
        protected int highWater;
        private int mark;
        private int largestMark;
        private boolean closed;

        private GeneratedStream(byte[] prefix, int size) {
            this.prefix = prefix;
            this.size = size;
        }

        protected int byteAt(int offset) {
            return offset < prefix.length ? prefix[offset] & 0xff : 0;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (position == size) {
                return -1;
            }
            int count = Math.min(length, size - position);
            for (int i = 0; i < count; i++) {
                bytes[offset + i] = (byte) byteAt(position++);
            }
            highWater = Math.max(highWater, position);
            return count;
        }

        @Override
        public boolean markSupported() {
            return true;
        }

        @Override
        public void mark(int limit) {
            mark = position;
            largestMark = Math.max(largestMark, limit);
        }

        @Override
        public void reset() throws IOException {
            position = mark;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
