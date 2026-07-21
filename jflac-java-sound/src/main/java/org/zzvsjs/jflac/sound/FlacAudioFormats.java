package org.zzvsjs.jflac.sound;

import org.zzvsjs.jflac.FlacMetadata;
import org.zzvsjs.jflac.FlacStreamInfo;
import org.zzvsjs.jflac.FlacVorbisComment;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FlacAudioFormats {
    static final String CONTAINER_NATIVE = "native";
    static final String CONTAINER_OGG = "ogg";

    private FlacAudioFormats() {
    }

    static AudioFormat pcmFormat(FlacStreamInfo info) {
        return pcmFormat(info, Map.of());
    }

    static AudioFormat pcmFormat(FlacStreamInfo info, Map<String, Object> properties) {
        int bytesPerSample = (info.getBitsPerSample() + 7) / 8;
        /*
         * Java Sound frame size is measured in whole bytes for one sample frame
         * across every channel. FLAC bit depths are not restricted to byte
         * boundaries, so round each channel up before multiplying by channels.
         */
        int frameSize = Math.multiplyExact(bytesPerSample, info.getChannels());
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                info.getSampleRate(),
                info.getBitsPerSample(),
                info.getChannels(),
                frameSize,
                info.getSampleRate(),
                false,
                Map.copyOf(properties)
        );
    }

    static AudioFileFormat fileFormat(FlacStreamInfo info) {
        return fileFormat(info, JflacAudioFileTypes.FLAC, CONTAINER_NATIVE);
    }

    static AudioFileFormat fileFormat(FlacStreamInfo info, AudioFileFormat.Type type, String container) {
        Map<String, Object> properties = streamInfoProperties(info, container);
        AudioFormat format = pcmFormat(info, properties);
        return new AudioFileFormat(type, format, frameLength(info), properties);
    }

    static AudioFileFormat fileFormat(FlacMetadata metadata, AudioFileFormat.Type type, String container) {
        Map<String, Object> properties = metadataProperties(metadata, container);
        AudioFormat format = pcmFormat(metadata.getStreamInfo(), properties);
        return new AudioFileFormat(type, format, frameLength(metadata.getStreamInfo()), properties);
    }

    static Map<String, Object> streamInfoProperties(FlacStreamInfo info, String container) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(JflacAudioFileProperties.CONTAINER, container);
        properties.put(JflacAudioFileProperties.SAMPLE_RATE, info.getSampleRate());
        properties.put(JflacAudioFileProperties.CHANNELS, info.getChannels());
        properties.put(JflacAudioFileProperties.BITS_PER_SAMPLE, info.getBitsPerSample());
        properties.put(JflacAudioFileProperties.TOTAL_SAMPLES, info.getTotalSamples());
        properties.put(JflacAudioFileProperties.MD5, info.getMd5Signature().clone());
        if (info.getTotalSamples() > 0 && info.getSampleRate() > 0) {
            /*
             * Java Sound represents duration in microseconds. Compute with Long
             * arithmetic so large files either remain exact enough for the SPI
             * property or fail loudly if they overflow before division.
             */
            long duration = Math.multiplyExact(info.getTotalSamples(), 1_000_000L) / info.getSampleRate();
            properties.put("duration", duration);
        }

        return Map.copyOf(properties);
    }

    private static Map<String, Object> metadataProperties(FlacMetadata metadata, String container) {
        Map<String, Object> properties = new HashMap<>(streamInfoProperties(metadata.getStreamInfo(), container));
        FlacVorbisComment vorbisComment = metadata.getVorbisComment();
        if (vorbisComment != null) {
            properties.put(JflacAudioFileProperties.VORBIS_VENDOR, vorbisComment.getVendor());
            properties.put(JflacAudioFileProperties.VORBIS_COMMENTS, copyComments(vorbisComment.getComments()));
        }

        putIfNotEmpty(properties, JflacAudioFileProperties.PICTURES, metadata.getPictures());
        putIfNotEmpty(properties, JflacAudioFileProperties.APPLICATION_BLOCKS, metadata.getApplicationBlocks());
        putIfNotEmpty(properties, JflacAudioFileProperties.SEEK_TABLES, metadata.getSeekTables());
        putIfNotEmpty(properties, JflacAudioFileProperties.CUE_SHEETS, metadata.getCueSheets());
        putIfNotEmpty(properties, JflacAudioFileProperties.PADDING_BLOCKS, metadata.getPaddingBlocks());
        putIfNotEmpty(properties, JflacAudioFileProperties.UNKNOWN_BLOCKS, metadata.getUnknownBlocks());
        putIfNotEmpty(properties, JflacAudioFileProperties.BLOCKS, metadata.getBlocks());
        return Map.copyOf(properties);
    }

    private static Map<String, List<String>> copyComments(Map<String, List<String>> comments) {
        Map<String, List<String>> copied = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : comments.entrySet()) {
            copied.put(entry.getKey(), List.copyOf(entry.getValue()));
        }

        return Map.copyOf(copied);
    }

    private static void putIfNotEmpty(Map<String, Object> properties, String key, List<?> values) {
        if (!values.isEmpty()) {
            properties.put(key, List.copyOf(values));
        }
    }

    static int frameLength(FlacStreamInfo info) {
        /*
         * FLAC stores zero total samples when the encoder cannot know the final
         * length up front, for example with non-seekable output. Java Sound uses
         * frame length zero as a real empty stream, so expose unknown or too-large
         * lengths with its sentinel instead.
         */
        if (info.getTotalSamples() <= 0 || info.getTotalSamples() > Integer.MAX_VALUE) {
            return AudioSystem.NOT_SPECIFIED;
        }

        return Math.toIntExact(info.getTotalSamples());
    }
}
