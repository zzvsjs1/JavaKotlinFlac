package org.zzvsjs.jflac.sound;

import org.junit.jupiter.api.Test;
import org.zzvsjs.jflac.FlacAudioFormat;
import org.zzvsjs.jflac.FlacEncoder;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.spi.AudioFileReader;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.util.Base64;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class FlacAudioFileReaderTest {
    @Test
    public void serviceLoaderFindsFlacAudioFileReader() {
        boolean found = false;
        for (AudioFileReader reader : ServiceLoader.load(AudioFileReader.class)) {
            if (reader instanceof FlacAudioFileReader) {
                found = true;
                break;
            }
        }

        assertTrue(found, "Java Sound service loader should find the jflac FLAC reader.");
    }

    @Test
    public void audioSystemReadsFileFormat() throws Exception {
        File file = TestFlacFixtures.createFlacFile(17, 44_100, 2, 16);
        try {
            AudioFileFormat fileFormat = AudioSystem.getAudioFileFormat(file);
            AudioFormat audioFormat = fileFormat.getFormat();

            assertEquals(JflacAudioFileTypes.FLAC, fileFormat.getType());
            assertEquals(17, fileFormat.getFrameLength());
            assertEquals(44_100f, audioFormat.getSampleRate(), 0.1f);
            assertEquals(2, audioFormat.getChannels());
            assertEquals(16, audioFormat.getSampleSizeInBits());
        } finally {
            file.delete();
        }
    }

    @Test
    public void nonFlacInputIsUnsupported() throws Exception {
        assertThrows(
                UnsupportedAudioFileException.class,
                () -> new FlacAudioFileReader().getAudioFileFormat(
                        new ByteArrayInputStream(new byte[] { 'R', 'I', 'F', 'F' })
                )
        );
    }

    @Test
    public void streamFormatProbeRestoresCallerPosition() throws Exception {
        File file = TestFlacFixtures.createFlacFile(20_000, 44_100, 2, 16);
        try (InputStream stream = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
            AudioFileFormat fileFormat = new FlacAudioFileReader().getAudioFileFormat(stream);

            assertEquals(JflacAudioFileTypes.FLAC, fileFormat.getType());
            assertArrayEquals(new byte[] { 'f', 'L', 'a', 'C' }, stream.readNBytes(4));
        } finally {
            file.delete();
        }
    }

    @Test
    public void streamDecodeRejectionRestoresCallerPosition() throws Exception {
        ByteArrayInputStream stream = new ByteArrayInputStream(new byte[] { 'R', 'I', 'F', 'F', 'x' });

        assertThrows(
                UnsupportedAudioFileException.class,
                () -> new FlacAudioFileReader().getAudioInputStream(stream),
                "Non-FLAC stream decode should be rejected."
        );
        assertArrayEquals(new byte[] { 'R', 'I', 'F', 'F', 'x' }, stream.readNBytes(5));
    }

    @Test
    public void streamDecodeSuccessDoesNotInstallHugeCallerMark() throws Exception {
        File file = TestFlacFixtures.createFlacFile(20_000, 44_100, 2, 16);
        try (TrackingBufferedInputStream stream = new TrackingBufferedInputStream(Files.newInputStream(file.toPath()));
             AudioInputStream input = new FlacAudioFileReader().getAudioInputStream(stream)) {
            byte[] bytes = input.readNBytes(16);

            assertEquals(16, bytes.length);
            assertTrue(stream.maxReadlimit <= 4, "Successful stream decode should only use short caller marks.");
        } finally {
            file.delete();
        }
    }

    @Test
    public void audioSystemReadsPcmBytesFromFile() throws Exception {
        File file = TestFlacFixtures.createFlacFile(23, 44_100, 2, 16);
        try (AudioInputStream input = AudioSystem.getAudioInputStream(file)) {
            byte[] bytes = readAllBytes(input, 5);

            assertEquals(23 * 2 * 2, bytes.length);
            assertEquals(AudioFormat.Encoding.PCM_SIGNED, input.getFormat().getEncoding());
            assertEquals(4, input.getFormat().getFrameSize());
            assertFalse(input.getFormat().isBigEndian());
        } finally {
            file.delete();
        }
    }

    @Test
    public void unknownTotalSamplesRemainReadableFromStream() throws Exception {
        int frames = 19;
        int sampleRate = 44_100;
        int channels = 2;
        int bitsPerSample = 16;
        byte[] flacBytes = TestFlacFixtures.createUnknownTotalSamplesFlac(
                frames,
                sampleRate,
                channels,
                bitsPerSample
        );

        try (AudioInputStream input = AudioSystem.getAudioInputStream(new ByteArrayInputStream(flacBytes))) {
            byte[] bytes = readAllBytes(input, 7);

            assertEquals(AudioSystem.NOT_SPECIFIED, input.getFrameLength());
            assertEquals(frames * channels * (bitsPerSample / 8), bytes.length);
        }
    }

    @Test
    public void fileDecodeRejectsOggFlacForNativeJavaSoundSpi() throws Exception {
        File file = TestFlacFixtures.createOggFlacFile();
        try {
            assertThrows(
                    UnsupportedAudioFileException.class,
                    () -> new FlacAudioFileReader().getAudioInputStream(file)
            );
        } finally {
            file.delete();
        }
    }

    @Test
    public void littleEndianConversionPreservesSignedSamples() {
        byte[] bytes = FlacPcmInputStream.encodeSampleForTest(-2, 16);

        assertArrayEquals(new byte[] { (byte) 0xfe, (byte) 0xff }, bytes);
    }

    @Test
    @SuppressWarnings("deprecation")
    public void nonFlacUrlDecodeClosesOpenedStream() throws Exception {
        CloseTrackingInputStream stream = new CloseTrackingInputStream(new byte[] { 'R', 'I', 'F', 'F' });
        URL url = new URL(null, "jflac-test://non-flac", new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL ignored) {
                return new URLConnection(ignored) {
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

        assertThrows(
                UnsupportedAudioFileException.class,
                () -> new FlacAudioFileReader().getAudioInputStream(url),
                "Non-FLAC URL decode should be rejected."
        );
        assertTrue(stream.closed, "Rejected URL decode should close the opened URL stream.");
    }

    private static byte[] readAllBytes(AudioInputStream input, int bufferSize) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[bufferSize];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private CloseTrackingInputStream(byte[] buffer) {
            super(buffer);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class TrackingBufferedInputStream extends BufferedInputStream {
        private int maxReadlimit;

        private TrackingBufferedInputStream(InputStream input) {
            super(input);
        }

        @Override
        public synchronized void mark(int readlimit) {
            maxReadlimit = Math.max(maxReadlimit, readlimit);
            super.mark(readlimit);
        }
    }

    private static final class TestFlacFixtures {
        private static final String OGG_FLAC_FIXTURE_BASE64 = ""
                + "T2dnUwACAAAAAAAAAACAGnKDAAAAAOUMOC4BM39GTEFDAQAAAWZMYUMAAAAiAkACQAAAAAAElQH0APAA\n"
                + "AAAAAAAAAAAAAAAAAAAAAAAAAE9nZ1MAAAAAAAAAAAAAgBpygwEAAABaeJ3IAVCEAABMDAAAAExhdmY2\n"
                + "Mi4zLjEwMAIAAAAaAAAAZW5jb2Rlcj1MYXZjNjIuMTEuMTAwIGZsYWMWAAAAdGl0bGU9T2dnIEZMQUMg\n"
                + "Rml4dHVyZU9nZ1MABFAAAAAAAAAAgBpygwIAAAD8m/WsAWL/+GQIAE8JTgAABWsKMg3FD7YPzQ4FCpTl\n"
                + "mJO/JGtbXhj3jqRBkn77gI6TffVzFBQs4acetz6Lr5N1u+UFHChZ57mLqrkyL7PZ4UIEDDxBDFUVyrJl\n"
                + "NWNFmChQkQ8lnYAQIg==\n";

        private TestFlacFixtures() {
        }

        static File createFlacFile(int frames, int sampleRate, int channels, int bitsPerSample) throws IOException {
            File file = File.createTempFile("jflac-java-sound-", ".flac");
            FlacAudioFormat format = new FlacAudioFormat(sampleRate, channels, bitsPerSample, (long) frames);
            new FlacEncoder().encode(
                    file.toPath(),
                    format,
                    deterministicPcm(frames, channels, bitsPerSample)
            );
            return file;
        }

        static byte[] createUnknownTotalSamplesFlac(
                int frames,
                int sampleRate,
                int channels,
                int bitsPerSample
        ) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            FlacAudioFormat format = new FlacAudioFormat(sampleRate, channels, bitsPerSample);
            new FlacEncoder().encode(
                    output,
                    format,
                    deterministicPcm(frames, channels, bitsPerSample)
            );
            return output.toByteArray();
        }

        static File createOggFlacFile() throws IOException {
            File file = File.createTempFile("jflac-java-sound-ogg-", ".oga");
            Files.write(file.toPath(), Base64.getMimeDecoder().decode(OGG_FLAC_FIXTURE_BASE64));
            return file;
        }

        private static int[] deterministicPcm(int frames, int channels, int bitsPerSample) {
            int[] samples = new int[frames * channels];
            int minSample = -(1 << (bitsPerSample - 1));
            int range = 1 << bitsPerSample;
            int sampleIndex = 0;
            for (int frame = 0; frame < frames; frame++) {
                for (int channel = 0; channel < channels; channel++) {
                    samples[sampleIndex++] = ((frame * 97 + channel * 151) % range) + minSample;
                }
            }
            return samples;
        }
    }
}
