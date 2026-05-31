package org.zzvsjs.jflac.sound;

import org.junit.jupiter.api.Test;
import org.zzvsjs.jflac.FlacApplicationBlock;
import org.zzvsjs.jflac.FlacAudioFormat;
import org.zzvsjs.jflac.FlacEncodingMetadata;
import org.zzvsjs.jflac.FlacEncoder;
import org.zzvsjs.jflac.FlacMetadataBlock;
import org.zzvsjs.jflac.FlacPaddingBlock;
import org.zzvsjs.jflac.FlacPicture;
import org.zzvsjs.jflac.FlacSeekPoint;
import org.zzvsjs.jflac.FlacSeekTable;

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
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class FlacAudioFileReaderTest {
    @Test
    public void publicConstantsDescribeSupportedFileTypesAndProperties() {
        assertEquals("FLAC", JflacAudioFileTypes.FLAC.toString());
        assertEquals("flac", JflacAudioFileTypes.FLAC.getExtension());
        assertEquals("Ogg FLAC", JflacAudioFileTypes.OGG_FLAC.toString());
        assertEquals("oga", JflacAudioFileTypes.OGG_FLAC.getExtension());

        assertEquals("flac.container", JflacAudioFileProperties.CONTAINER);
        assertEquals("flac.sampleRate", JflacAudioFileProperties.SAMPLE_RATE);
        assertEquals("flac.channels", JflacAudioFileProperties.CHANNELS);
        assertEquals("flac.bitsPerSample", JflacAudioFileProperties.BITS_PER_SAMPLE);
        assertEquals("flac.totalSamples", JflacAudioFileProperties.TOTAL_SAMPLES);
        assertEquals("flac.md5", JflacAudioFileProperties.MD5);
        assertEquals("flac.vorbis.vendor", JflacAudioFileProperties.VORBIS_VENDOR);
        assertEquals("flac.vorbis.comments", JflacAudioFileProperties.VORBIS_COMMENTS);
        assertEquals("flac.pictures", JflacAudioFileProperties.PICTURES);
        assertEquals("flac.applicationBlocks", JflacAudioFileProperties.APPLICATION_BLOCKS);
        assertEquals("flac.seekTables", JflacAudioFileProperties.SEEK_TABLES);
        assertEquals("flac.cueSheets", JflacAudioFileProperties.CUE_SHEETS);
        assertEquals("flac.paddingBlocks", JflacAudioFileProperties.PADDING_BLOCKS);
        assertEquals("flac.unknownBlocks", JflacAudioFileProperties.UNKNOWN_BLOCKS);
        assertEquals("flac.blocks", JflacAudioFileProperties.BLOCKS);
    }

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
    public void oggLikeStreamDecodeRejectionRestoresCallerPosition() throws Exception {
        byte[] bytes = new byte[] { 'O', 'g', 'g', 'S', 'x', 'x' };
        ByteArrayInputStream stream = new ByteArrayInputStream(bytes);

        assertThrows(
                UnsupportedAudioFileException.class,
                () -> new FlacAudioFileReader().getAudioInputStream(stream),
                "Non-FLAC Ogg stream decode should be rejected."
        );
        assertArrayEquals(bytes, stream.readNBytes(bytes.length));
    }

    @Test
    public void streamDecodeSuccessContinuesAfterSetupProbe() throws Exception {
        File file = TestFlacFixtures.createFlacFile(20_000, 44_100, 2, 16);
        try (InputStream stream = new BufferedInputStream(Files.newInputStream(file.toPath()));
             AudioInputStream input = new FlacAudioFileReader().getAudioInputStream(stream)) {
            byte[] bytes = input.readNBytes(16);

            assertEquals(16, bytes.length);
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
    public void audioSystemReadsOggFlacFileFormatAndPcmBytes() throws Exception {
        File file = TestFlacFixtures.createOggFlacFile();
        try {
            AudioFileFormat fileFormat = AudioSystem.getAudioFileFormat(file);

            assertEquals(JflacAudioFileTypes.OGG_FLAC, fileFormat.getType());
            assertEquals("ogg", fileFormat.properties().get(JflacAudioFileProperties.CONTAINER));

            try (AudioInputStream input = AudioSystem.getAudioInputStream(file)) {
                byte[] bytes = readAllBytes(input, 3);
                assertTrue(bytes.length > 0, "Ogg FLAC Java Sound decode should produce PCM bytes.");
                assertEquals(AudioFormat.Encoding.PCM_SIGNED, input.getFormat().getEncoding());
                assertEquals("ogg", input.getFormat().properties().get(JflacAudioFileProperties.CONTAINER));
            }
        } finally {
            file.delete();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void fileBackedFormatIncludesRichMetadataProperties() throws Exception {
        File file = TestFlacFixtures.createRichMetadataFlacFile();
        try {
            AudioFileFormat fileFormat = AudioSystem.getAudioFileFormat(file);
            Map<String, Object> properties = fileFormat.properties();

            assertEquals("native", properties.get(JflacAudioFileProperties.CONTAINER));
            assertEquals(44_100, properties.get(JflacAudioFileProperties.SAMPLE_RATE));
            assertEquals(2, properties.get(JflacAudioFileProperties.CHANNELS));
            assertEquals(16, properties.get(JflacAudioFileProperties.BITS_PER_SAMPLE));
            assertEquals(12L, properties.get(JflacAudioFileProperties.TOTAL_SAMPLES));
            assertTrue(properties.get(JflacAudioFileProperties.MD5) instanceof byte[]);

            Map<String, List<String>> comments =
                    (Map<String, List<String>>) properties.get(JflacAudioFileProperties.VORBIS_COMMENTS);
            assertEquals(List.of("Reader metadata"), comments.get("TITLE"));
            assertEquals(1, ((List<FlacPicture>) properties.get(JflacAudioFileProperties.PICTURES)).size());
            assertEquals(1, ((List<FlacApplicationBlock>) properties.get(JflacAudioFileProperties.APPLICATION_BLOCKS)).size());
            assertEquals(1, ((List<FlacSeekTable>) properties.get(JflacAudioFileProperties.SEEK_TABLES)).size());
            assertEquals(1, ((List<FlacPaddingBlock>) properties.get(JflacAudioFileProperties.PADDING_BLOCKS)).size());
            assertEquals(5, ((List<FlacMetadataBlock>) properties.get(JflacAudioFileProperties.BLOCKS)).size());
        } finally {
            file.delete();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void fileBackedAudioInputStreamFormatCarriesRichMetadataProperties() throws Exception {
        File file = TestFlacFixtures.createRichMetadataFlacFile();
        try (AudioInputStream input = AudioSystem.getAudioInputStream(file)) {
            Map<String, Object> properties = input.getFormat().properties();

            assertEquals("native", properties.get(JflacAudioFileProperties.CONTAINER));
            Map<String, List<String>> comments =
                    (Map<String, List<String>>) properties.get(JflacAudioFileProperties.VORBIS_COMMENTS);
            assertEquals(List.of("Reader metadata"), comments.get("TITLE"));
            assertEquals(5, ((List<FlacMetadataBlock>) properties.get(JflacAudioFileProperties.BLOCKS)).size());
        } finally {
            file.delete();
        }
    }

    @Test
    public void streamBackedFormatOnlyExposesStreamInfoProperties() throws Exception {
        File file = TestFlacFixtures.createRichMetadataFlacFile();
        try (InputStream stream = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
            AudioFileFormat fileFormat = new FlacAudioFileReader().getAudioFileFormat(stream);
            Map<String, Object> properties = fileFormat.properties();

            assertEquals("native", properties.get(JflacAudioFileProperties.CONTAINER));
            assertEquals(44_100, properties.get(JflacAudioFileProperties.SAMPLE_RATE));
            assertFalse(properties.containsKey(JflacAudioFileProperties.VORBIS_COMMENTS));
            assertFalse(properties.containsKey(JflacAudioFileProperties.BLOCKS));
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

        static File createRichMetadataFlacFile() throws IOException {
            File file = File.createTempFile("jflac-java-sound-rich-", ".flac");
            int frames = 12;
            int sampleRate = 44_100;
            int channels = 2;
            int bitsPerSample = 16;
            FlacAudioFormat format = new FlacAudioFormat(sampleRate, channels, bitsPerSample, (long) frames);
            FlacPicture picture = new FlacPicture(
                    3,
                    "image/png",
                    "Cover",
                    1,
                    1,
                    24,
                    0,
                    new byte[] { 1, 2, 3, 4 }
            );
            FlacApplicationBlock application = new FlacApplicationBlock(
                    new byte[] { 'J', 'F', 'L', 'C' },
                    new byte[] { 5, 6, 7 }
            );
            FlacSeekTable seekTable = new FlacSeekTable(List.of(new FlacSeekPoint(0L, 0L, frames)));
            FlacPaddingBlock padding = new FlacPaddingBlock(32);

            new FlacEncoder().encode(
                    file.toPath(),
                    format,
                    deterministicPcm(frames, channels, bitsPerSample),
                    new FlacEncodingMetadata(
                            Map.of("TITLE", List.of("Reader metadata")),
                            List.of(picture),
                            List.of(application),
                            List.of(seekTable),
                            List.of(),
                            List.of(padding),
                            List.of(),
                            List.of()
                    )
            );
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
