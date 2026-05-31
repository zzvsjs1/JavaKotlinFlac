package org.zzvsjs.jflac.sound;

import org.zzvsjs.jflac.FlacAudioFormat;
import org.zzvsjs.jflac.FlacApplicationBlock;
import org.zzvsjs.jflac.FlacCueSheet;
import org.zzvsjs.jflac.FlacEncoder;
import org.zzvsjs.jflac.FlacEncodingContainer;
import org.zzvsjs.jflac.FlacEncodingMetadata;
import org.zzvsjs.jflac.FlacEncodingOptions;
import org.zzvsjs.jflac.FlacEncodingSession;
import org.zzvsjs.jflac.FlacException;
import org.zzvsjs.jflac.FlacMetadataBlock;
import org.zzvsjs.jflac.FlacPaddingBlock;
import org.zzvsjs.jflac.FlacPicture;
import org.zzvsjs.jflac.FlacSeekTable;
import org.zzvsjs.jflac.FlacUnknownMetadataBlock;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.spi.AudioFileWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Java Sound service provider entry point for writing FLAC files.
 *
 * <p>The writer accepts Java Sound PCM streams and forwards bounded interleaved
 * chunks to the core libFLAC encoder. Supported FLAC metadata can be supplied
 * through the input format properties.</p>
 */
public final class FlacAudioFileWriter extends AudioFileWriter {
    private static final int BUFFER_FRAMES = 1024;
    private static final AudioFileFormat.Type[] SUPPORTED_TYPES = new AudioFileFormat.Type[] {
            JflacAudioFileTypes.FLAC,
            JflacAudioFileTypes.OGG_FLAC
    };

    @Override
    public AudioFileFormat.Type[] getAudioFileTypes() {
        return SUPPORTED_TYPES.clone();
    }

    @Override
    public AudioFileFormat.Type[] getAudioFileTypes(AudioInputStream stream) {
        Objects.requireNonNull(stream, "stream");
        if (isPcmFormatSupported(stream)) {
            return getAudioFileTypes();
        }
        return new AudioFileFormat.Type[0];
    }

    @Override
    public boolean isFileTypeSupported(AudioFileFormat.Type fileType) {
        return JflacAudioFileTypes.FLAC.equals(fileType) || JflacAudioFileTypes.OGG_FLAC.equals(fileType);
    }

    @Override
    public boolean isFileTypeSupported(AudioFileFormat.Type fileType, AudioInputStream stream) {
        Objects.requireNonNull(stream, "stream");
        return isFileTypeSupported(fileType) && isPcmFormatSupported(stream);
    }

    @Override
    public int write(AudioInputStream stream, AudioFileFormat.Type fileType, OutputStream output)
            throws IOException {
        Objects.requireNonNull(output, "output");
        PcmSampleReader reader = checkedReader(stream, fileType);
        CountingOutputStream countingOutput = new CountingOutputStream(output);
        FlacAudioFormat format = flacFormat(reader);
        FlacEncodingMetadata metadata = metadataFromProperties(reader.format().properties());
        FlacEncodingOptions options = encodingOptions(fileType);

        try (FlacEncodingSession session = new FlacEncoder().open(
                countingOutput,
                format,
                metadata,
                options
        )) {
            writePcm(reader, session);
        } catch (FlacException e) {
            throw new IOException("Failed to encode FLAC through Java Sound.", e);
        }
        return byteCount(countingOutput.bytesWritten());
    }

    @Override
    public int write(AudioInputStream stream, AudioFileFormat.Type fileType, File output)
            throws IOException {
        Objects.requireNonNull(output, "output");
        PcmSampleReader reader = checkedReader(stream, fileType);
        FlacAudioFormat format = flacFormat(reader);
        FlacEncodingMetadata metadata = metadataFromProperties(reader.format().properties());
        FlacEncodingOptions options = encodingOptions(fileType);

        try (FlacEncodingSession session = new FlacEncoder().open(
                output.toPath(),
                format,
                metadata,
                options
        )) {
            writePcm(reader, session);
        } catch (FlacException e) {
            throw new IOException("Failed to encode FLAC through Java Sound.", e);
        }
        return byteCount(Files.size(output.toPath()));
    }

    private static PcmSampleReader checkedReader(AudioInputStream stream, AudioFileFormat.Type fileType) {
        Objects.requireNonNull(stream, "stream");
        if (!isSupportedFileType(fileType)) {
            throw new IllegalArgumentException("Unsupported FLAC target file type: " + fileType);
        }
        return new PcmSampleReader(stream);
    }

    private static boolean isSupportedFileType(AudioFileFormat.Type fileType) {
        return JflacAudioFileTypes.FLAC.equals(fileType) || JflacAudioFileTypes.OGG_FLAC.equals(fileType);
    }

    private static boolean isPcmFormatSupported(AudioInputStream stream) {
        try {
            new PcmSampleReader(stream);
            return true;
        } catch (IllegalArgumentException | ArithmeticException e) {
            return false;
        }
    }

    private static FlacAudioFormat flacFormat(PcmSampleReader reader) {
        return new FlacAudioFormat(
                reader.sampleRate(),
                reader.channels(),
                reader.bitsPerSample(),
                reader.totalSamplesEstimate()
        );
    }

    private static FlacEncodingOptions encodingOptions(AudioFileFormat.Type fileType) {
        FlacEncodingContainer container = container(fileType);
        return new FlacEncodingOptions(5, true, true, null, container, null);
    }

    private static FlacEncodingMetadata metadataFromProperties(Map<String, Object> properties) {
        if (properties.containsKey(JflacAudioFileProperties.BLOCKS)) {
            List<FlacMetadataBlock> blocks = listProperty(
                    properties,
                    JflacAudioFileProperties.BLOCKS,
                    FlacMetadataBlock.class
            );
            return new FlacEncodingMetadata(
                    Collections.emptyMap(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    blocks
            );
        }

        return new FlacEncodingMetadata(
                commentsProperty(properties),
                listProperty(properties, JflacAudioFileProperties.PICTURES, FlacPicture.class),
                listProperty(properties, JflacAudioFileProperties.APPLICATION_BLOCKS, FlacApplicationBlock.class),
                listProperty(properties, JflacAudioFileProperties.SEEK_TABLES, FlacSeekTable.class),
                listProperty(properties, JflacAudioFileProperties.CUE_SHEETS, FlacCueSheet.class),
                listProperty(properties, JflacAudioFileProperties.PADDING_BLOCKS, FlacPaddingBlock.class),
                listProperty(properties, JflacAudioFileProperties.UNKNOWN_BLOCKS, FlacUnknownMetadataBlock.class),
                Collections.emptyList()
        );
    }

    private static Map<String, List<String>> commentsProperty(Map<String, Object> properties) {
        if (!properties.containsKey(JflacAudioFileProperties.VORBIS_COMMENTS)) {
            return Collections.emptyMap();
        }

        Object value = properties.get(JflacAudioFileProperties.VORBIS_COMMENTS);
        if (!(value instanceof Map<?, ?> comments)) {
            throw new IllegalArgumentException(
                    JflacAudioFileProperties.VORBIS_COMMENTS + " must be a Map<String, List<String>>."
            );
        }

        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : comments.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(
                        JflacAudioFileProperties.VORBIS_COMMENTS + " must have String keys."
                );
            }
            if (!(entry.getValue() instanceof List<?> values)) {
                throw new IllegalArgumentException(
                        JflacAudioFileProperties.VORBIS_COMMENTS + " values must be List<String>."
                );
            }
            for (Object item : values) {
                if (!(item instanceof String)) {
                    throw new IllegalArgumentException(
                            JflacAudioFileProperties.VORBIS_COMMENTS + " values must be List<String>."
                    );
                }
            }
            copy.put(key, List.copyOf(values.stream().map(String.class::cast).toList()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static <T> List<T> listProperty(Map<String, Object> properties, String key, Class<T> itemType) {
        if (!properties.containsKey(key)) {
            return Collections.emptyList();
        }

        Object value = properties.get(key);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(key + " must be a List<" + itemType.getSimpleName() + ">.");
        }
        for (Object item : list) {
            if (!itemType.isInstance(item)) {
                throw new IllegalArgumentException(key + " must be a List<" + itemType.getSimpleName() + ">.");
            }
        }
        return List.copyOf(list.stream().map(itemType::cast).toList());
    }

    private static FlacEncodingContainer container(AudioFileFormat.Type fileType) {
        if (JflacAudioFileTypes.FLAC.equals(fileType)) {
            return FlacEncodingContainer.NATIVE;
        }
        if (JflacAudioFileTypes.OGG_FLAC.equals(fileType)) {
            return FlacEncodingContainer.OGG;
        }
        throw new IllegalArgumentException("Unsupported FLAC target file type: " + fileType);
    }

    private static void writePcm(PcmSampleReader reader, FlacEncodingSession session) throws IOException {
        int channels = reader.channels();
        int[] samples = new int[Math.multiplyExact(BUFFER_FRAMES, channels)];
        int framesRead;
        while ((framesRead = reader.readFrames(samples, BUFFER_FRAMES)) != -1) {
            int sampleCount = Math.multiplyExact(framesRead, channels);
            int[] chunk = sampleCount == samples.length ? samples : Arrays.copyOf(samples, sampleCount);
            session.writeInterleaved(chunk, framesRead);
        }
    }

    private static int byteCount(long bytes) {
        if (bytes > Integer.MAX_VALUE) {
            return AudioSystem.NOT_SPECIFIED;
        }
        return Math.toIntExact(bytes);
    }
}
