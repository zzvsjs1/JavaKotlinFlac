package org.zzvsjs.jflac.sound;

import org.junit.jupiter.api.Test;
import org.zzvsjs.jflac.FlacApplicationBlock;
import org.zzvsjs.jflac.FlacDecodedAudio;
import org.zzvsjs.jflac.FlacDecoder;
import org.zzvsjs.jflac.FlacMetadata;
import org.zzvsjs.jflac.FlacMetadataBlock;
import org.zzvsjs.jflac.FlacMetadataReader;
import org.zzvsjs.jflac.FlacPaddingBlock;
import org.zzvsjs.jflac.FlacPicture;
import org.zzvsjs.jflac.FlacSeekPoint;
import org.zzvsjs.jflac.FlacSeekTable;
import org.zzvsjs.jflac.FlacVorbisComment;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.spi.AudioFileWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class FlacAudioFileWriterTest {
    @Test
    public void serviceLoaderFindsFlacAudioFileWriter() {
        boolean found = false;
        for (AudioFileWriter writer : ServiceLoader.load(AudioFileWriter.class)) {
            if (writer instanceof FlacAudioFileWriter) {
                found = true;
                break;
            }
        }

        assertTrue(found, "Java Sound service loader should find the jflac FLAC writer.");
    }

    @Test
    public void audioSystemWritesNativeFlacFile() throws Exception {
        int[] samples = new int[] { -100, 100, -50, 50 };
        File file = File.createTempFile("jflac-java-sound-write", ".flac");
        try {
            int bytes = AudioSystem.write(input(samples, signed16(false), 2), JflacAudioFileTypes.FLAC, file);

            assertTrue(bytes > 0);
            FlacDecodedAudio decoded = new FlacDecoder().decode(file.toPath());
            assertArrayEquals(samples, decoded.getInterleavedSamples());
            assertEquals(2, decoded.getTotalFrames());
        } finally {
            file.delete();
        }
    }

    @Test
    public void audioSystemWritesOggFlacFile() throws Exception {
        int[] samples = new int[] { -10, 10, -20, 20 };
        File file = File.createTempFile("jflac-java-sound-write", ".oga");
        try {
            AudioSystem.write(input(samples, signed16(false), 2), JflacAudioFileTypes.OGG_FLAC, file);

            AudioFileFormat format = AudioSystem.getAudioFileFormat(file);
            assertEquals(JflacAudioFileTypes.OGG_FLAC, format.getType());
            assertArrayEquals(samples, new FlacDecoder().decode(file.toPath()).getInterleavedSamples());
        } finally {
            file.delete();
        }
    }

    @Test
    public void outputStreamWriteReturnsEncodedByteCount() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int bytes = AudioSystem.write(
                input(new int[] { -1, 0, 1, 2, 3, 4 }, signed16(false), 3),
                JflacAudioFileTypes.FLAC,
                output
        );

        assertEquals(output.size(), bytes);
        assertTrue(bytes > 0);
    }

    @Test
    public void outputStreamWriteDoesNotCloseCallerStream() throws Exception {
        CloseTrackingOutputStream output = new CloseTrackingOutputStream();

        AudioSystem.write(
                input(new int[] { -1, 0, 1, 2 }, signed16(false), 2),
                JflacAudioFileTypes.FLAC,
                output
        );

        assertFalse(output.closed, "Java Sound writer should not close caller-owned OutputStream.");
        assertTrue(output.size() > 0);
    }

    @Test
    public void writerPreservesRichMetadataFromAudioFormatProperties() throws Exception {
        FlacPicture picture = new FlacPicture(3, "image/png", "Cover", 1, 1, 24, 0, new byte[] { 1, 2, 3 });
        FlacApplicationBlock application = new FlacApplicationBlock(new byte[] { 'J', 'S', 'N', 'D' }, new byte[] { 4, 5 });
        FlacSeekTable seekTable = new FlacSeekTable(List.of(new FlacSeekPoint(0L, 0L, 2)));
        FlacPaddingBlock padding = new FlacPaddingBlock(16);
        Map<String, Object> properties = new HashMap<>();
        properties.put(JflacAudioFileProperties.BLOCKS, List.of(
                new FlacMetadataBlock.VorbisComment(new FlacVorbisComment("java-sound-test", Map.of("TITLE", List.of("Writer metadata")))),
                new FlacMetadataBlock.Picture(picture),
                new FlacMetadataBlock.Application(application),
                new FlacMetadataBlock.SeekTable(seekTable),
                new FlacMetadataBlock.Padding(padding)
        ));
        AudioFormat format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                44_100f,
                16,
                2,
                4,
                44_100f,
                false,
                properties
        );
        File file = File.createTempFile("jflac-java-sound-metadata-write", ".flac");
        try {
            AudioSystem.write(input(new int[] { -100, 100, -50, 50 }, format, 2), JflacAudioFileTypes.FLAC, file);

            FlacMetadata metadata = new FlacMetadataReader().read(file.toPath());
            assertEquals("java-sound-test", metadata.getVorbisComment().getVendor());
            assertEquals(List.of("Writer metadata"), metadata.getVorbisComment().getComments().get("TITLE"));
            assertEquals(List.of(picture), metadata.getPictures());
            assertEquals(List.of(application), metadata.getApplicationBlocks());
            assertEquals(List.of(seekTable), metadata.getSeekTables());
            assertEquals(List.of(padding), metadata.getPaddingBlocks());
        } finally {
            file.delete();
        }
    }

    @Test
    public void writerPreservesGroupedVorbisCommentsFromAudioFormatProperties() throws Exception {
        Map<String, Object> properties = new HashMap<>();
        properties.put(JflacAudioFileProperties.VORBIS_COMMENTS, Map.of("TITLE", List.of("Grouped writer metadata")));
        AudioFormat format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                44_100f,
                16,
                2,
                4,
                44_100f,
                false,
                properties
        );
        File file = File.createTempFile("jflac-java-sound-grouped-metadata-write", ".flac");
        try {
            AudioSystem.write(input(new int[] { -100, 100, -50, 50, 0, 1 }, format, 3), JflacAudioFileTypes.FLAC, file);

            FlacMetadata metadata = new FlacMetadataReader().read(file.toPath());
            assertFalse(metadata.getVorbisComment().getVendor().isBlank());
            assertEquals(List.of("Grouped writer metadata"), metadata.getVorbisComment().getComments().get("TITLE"));
        } finally {
            file.delete();
        }
    }

    @Test
    public void emptyOrderedBlocksSuppressGroupedMetadataProperties() throws Exception {
        Map<String, Object> properties = new HashMap<>();
        properties.put(JflacAudioFileProperties.BLOCKS, List.of());
        properties.put(JflacAudioFileProperties.VORBIS_COMMENTS, Map.of("TITLE", List.of("Ignored grouped metadata")));
        File file = File.createTempFile("jflac-java-sound-empty-blocks", ".flac");
        try {
            AudioSystem.write(
                    input(new int[] { -100, 100 }, formatWithProperties(properties), 1),
                    JflacAudioFileTypes.FLAC,
                    file
            );

            FlacMetadata metadata = new FlacMetadataReader().read(file.toPath());
            assertFalse(metadata.getVorbisComment().getComments().containsKey("TITLE"));
        } finally {
            file.delete();
        }
    }

    @Test
    public void oggWriterPreservesOrderedVorbisVendorFromAudioFormatProperties() throws Exception {
        Map<String, Object> properties = new HashMap<>();
        properties.put(JflacAudioFileProperties.BLOCKS, List.of(
                new FlacMetadataBlock.VorbisComment(new FlacVorbisComment("java-sound-ogg", Map.of("TITLE", List.of("Ogg metadata"))))
        ));
        File file = File.createTempFile("jflac-java-sound-ogg-metadata-write", ".oga");
        try {
            AudioSystem.write(
                    input(new int[] { -100, 100, -50, 50 }, formatWithProperties(properties), 2),
                    JflacAudioFileTypes.OGG_FLAC,
                    file
            );

            FlacMetadata metadata = new FlacMetadataReader().read(file.toPath());
            assertEquals("java-sound-ogg", metadata.getVorbisComment().getVendor());
            assertEquals(List.of("Ogg metadata"), metadata.getVorbisComment().getComments().get("TITLE"));
        } finally {
            file.delete();
        }
    }

    @Test
    public void outputStreamWriterPreservesOrderedMetadataFromAudioFormatProperties() throws Exception {
        Map<String, Object> properties = new HashMap<>();
        properties.put(JflacAudioFileProperties.BLOCKS, List.of(
                new FlacMetadataBlock.VorbisComment(new FlacVorbisComment("java-sound-stream", Map.of("TITLE", List.of("Stream metadata"))))
        ));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        File file = File.createTempFile("jflac-java-sound-stream-metadata-write", ".flac");
        try {
            AudioSystem.write(
                    input(new int[] { -100, 100 }, formatWithProperties(properties), 1),
                    JflacAudioFileTypes.FLAC,
                    output
            );
            Files.write(file.toPath(), output.toByteArray());

            FlacMetadata metadata = new FlacMetadataReader().read(file.toPath());
            assertEquals("java-sound-stream", metadata.getVorbisComment().getVendor());
            assertEquals(List.of("Stream metadata"), metadata.getVorbisComment().getComments().get("TITLE"));
        } finally {
            file.delete();
        }
    }

    @Test
    public void writerRejectsNonListMetadataProperty() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(JflacAudioFileProperties.PICTURES, "not a list");

        assertThrows(
                IllegalArgumentException.class,
                () -> new FlacAudioFileWriter().write(
                        input(new int[] { 0, 1 }, formatWithProperties(properties), 1),
                        JflacAudioFileTypes.FLAC,
                        new ByteArrayOutputStream()
                )
        );
    }

    @Test
    public void writerRejectsVorbisCommentValuesThatAreNotStringLists() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(JflacAudioFileProperties.VORBIS_COMMENTS, Map.of("TITLE", List.of(123)));

        assertThrows(
                IllegalArgumentException.class,
                () -> new FlacAudioFileWriter().write(
                        input(new int[] { 0, 1 }, formatWithProperties(properties), 1),
                        JflacAudioFileTypes.FLAC,
                        new ByteArrayOutputStream()
                )
        );
    }

    @Test
    public void writerRejectsNullMetadataPropertyValues() {
        Map<String, Object> listProperties = new HashMap<>();
        listProperties.put(JflacAudioFileProperties.PICTURES, null);
        assertThrows(
                IllegalArgumentException.class,
                () -> new FlacAudioFileWriter().write(
                        input(new int[] { 0, 1 }, formatWithProperties(listProperties), 1),
                        JflacAudioFileTypes.FLAC,
                        new ByteArrayOutputStream()
                )
        );

        Map<String, Object> commentProperties = new HashMap<>();
        commentProperties.put(JflacAudioFileProperties.VORBIS_COMMENTS, null);
        assertThrows(
                IllegalArgumentException.class,
                () -> new FlacAudioFileWriter().write(
                        input(new int[] { 0, 1 }, formatWithProperties(commentProperties), 1),
                        JflacAudioFileTypes.FLAC,
                        new ByteArrayOutputStream()
                )
        );
    }

    @Test
    public void unsupportedPcmFormatQueriesReturnUnsupportedWithoutThrowing() {
        FlacAudioFileWriter writer = new FlacAudioFileWriter();
        AudioInputStream unsupported = new AudioInputStream(
                new ByteArrayInputStream(new byte[8]),
                new AudioFormat(AudioFormat.Encoding.PCM_FLOAT, 44_100f, 32, 1, 4, 44_100f, false),
                2
        );

        assertArrayEquals(new AudioFileFormat.Type[0], writer.getAudioFileTypes(unsupported));
        assertFalse(writer.isFileTypeSupported(JflacAudioFileTypes.FLAC, unsupported));
    }

    @Test
    public void unsupportedTargetTypeIsRejectedByJflacWriter() throws Exception {
        AudioFileFormat.Type unsupported = new AudioFileFormat.Type("Unsupported", "unsupported");

        assertThrows(
                IllegalArgumentException.class,
                () -> new FlacAudioFileWriter().write(
                        input(new int[] { 0 }, signed16(false), 1),
                        unsupported,
                        new ByteArrayOutputStream()
                )
        );
    }

    private static AudioInputStream input(int[] samples, AudioFormat format, long frames) {
        byte[] bytes = new byte[samples.length * 2];
        for (int index = 0; index < samples.length; index++) {
            int value = samples[index];
            bytes[index * 2] = (byte) value;
            bytes[index * 2 + 1] = (byte) (value >> 8);
        }

        return new AudioInputStream(new ByteArrayInputStream(bytes), format, frames);
    }

    private static AudioFormat signed16(boolean bigEndian) {
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44_100f, 16, 2, 4, 44_100f, bigEndian);
    }

    private static AudioFormat formatWithProperties(Map<String, Object> properties) {
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                44_100f,
                16,
                2,
                4,
                44_100f,
                false,
                properties
        );
    }

    private static final class CloseTrackingOutputStream extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() throws java.io.IOException {
            closed = true;
            super.close();
        }
    }
}
