package org.zzvsjs.jflac.sound;

import org.zzvsjs.jflac.FlacStreamInfo;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import java.util.HashMap;
import java.util.Map;

final class FlacAudioFormats {
    private FlacAudioFormats() {
    }

    static AudioFormat pcmFormat(FlacStreamInfo info) {
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
                false
        );
    }

    static AudioFileFormat fileFormat(FlacStreamInfo info) {
        AudioFormat format = pcmFormat(info);
        Map<String, Object> properties = new HashMap<>();
        properties.put("flac.sampleRate", info.getSampleRate());
        properties.put("flac.channels", info.getChannels());
        properties.put("flac.bitsPerSample", info.getBitsPerSample());
        properties.put("flac.totalSamples", info.getTotalSamples());
        properties.put("flac.md5", info.getMd5Signature().clone());
        if (info.getTotalSamples() > 0 && info.getSampleRate() > 0) {
            /*
             * Java Sound represents duration in microseconds. Compute with Long
             * arithmetic so large files either remain exact enough for the SPI
             * property or fail loudly if they overflow before division.
             */
            long duration = Math.multiplyExact(info.getTotalSamples(), 1_000_000L) / info.getSampleRate();
            properties.put("duration", duration);
        }
        return new AudioFileFormat(JflacAudioFileTypes.FLAC, format, frameLength(info), properties);
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
