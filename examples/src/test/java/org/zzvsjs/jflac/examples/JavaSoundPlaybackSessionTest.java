package org.zzvsjs.jflac.examples;

import org.junit.jupiter.api.Test;
import org.zzvsjs.jflac.sound.JflacAudioFileProperties;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class JavaSoundPlaybackSessionTest {
    @Test
    public void frameAndTimeScalingUsesTheDeclaredRates() {
        assertEquals(1_000_000L, JavaSoundPlaybackSession.framesToMicros(44_100L, 44_100f));
        assertEquals(44_100L, JavaSoundPlaybackSession.microsToFrames(1_000_000L, 44_100f));
        assertEquals(44_100L, JavaSoundPlaybackSession.convertFrameCount(48_000L, 48_000f, 44_100f));
    }

    @Test
    public void longTotalSamplesPropertyWinsOverJavaSoundFrameSentinel() {
        long totalSamples = (long) Integer.MAX_VALUE + 10L;
        AudioFormat format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                44_100f,
                16,
                2,
                4,
                44_100f,
                false,
                Map.of(JflacAudioFileProperties.TOTAL_SAMPLES, totalSamples)
        );

        assertEquals(
                totalSamples,
                JavaSoundPlaybackSession.totalSourceFrames(format, AudioSystem.NOT_SPECIFIED)
        );
    }

    @Test
    public void streamFrameLengthIsUsedWhenMetadataTotalIsUnavailable() {
        AudioFormat format = pcmFormat(Map.of());

        assertEquals(123L, JavaSoundPlaybackSession.totalSourceFrames(format, 123L));
        assertEquals(-1L, JavaSoundPlaybackSession.totalSourceFrames(format, AudioSystem.NOT_SPECIFIED));
    }

    @Test
    public void pcmSeekSkipsWholeFrames() throws Exception {
        byte[] pcm = sequentialBytes(40);
        ByteArrayInputStream input = new ByteArrayInputStream(pcm);

        long frames = JavaSoundPlaybackSession.skipPcmFrames(input, 4, 6L);

        assertEquals(6L, frames);
        assertEquals(24, input.read());
    }

    @Test
    public void pcmSeekFallsBackToReadingWhenSkipMakesNoProgress() throws Exception {
        byte[] pcm = sequentialBytes(40);
        FilterInputStream input = new FilterInputStream(new ByteArrayInputStream(pcm)) {
            @Override
            public long skip(long count) {
                return 0L;
            }
        };

        long frames = JavaSoundPlaybackSession.skipPcmFrames(input, 4, 6L);

        assertEquals(6L, frames);
        assertEquals(24, input.read());
    }

    @Test
    public void pcmSeekStopsAtEndOfStream() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(sequentialBytes(20));

        assertEquals(5L, JavaSoundPlaybackSession.skipPcmFrames(input, 4, 100L));
    }

    @Test
    public void pcmSeekRejectsAStreamEndingInsideAFrame() {
        ByteArrayInputStream input = new ByteArrayInputStream(sequentialBytes(3));

        assertThrows(IOException.class, () -> JavaSoundPlaybackSession.skipPcmFrames(input, 2, 2L));
    }

    @Test
    public void pcmSeekCanBeSupersededBeforeFinishingTheOldTarget() {
        ByteArrayInputStream input = new ByteArrayInputStream(sequentialBytes(40));

        assertThrows(
                IOException.class,
                () -> JavaSoundPlaybackSession.skipPcmFrames(input, 4, 10L, () -> true)
        );
        assertEquals(40, input.available());
    }

    @Test
    public void progressRendererShowsKnownAndUnknownDurations() {
        PlaybackSession.Snapshot known = new PlaybackSession.Snapshot(
                Path.of("music.flac"),
                PlaybackSession.State.PLAYING,
                30_000_000L,
                120_000_000L,
                null
        );
        PlaybackSession.Snapshot unknown = new PlaybackSession.Snapshot(
                Path.of("stream.oga"),
                PlaybackSession.State.PAUSED,
                5_000_000L,
                -1L,
                null
        );

        assertEquals("[playing] 00:30 / 02:00   25%  music.flac", JavaSoundPlaybackDemo.renderProgress(known));
        assertEquals("[paused] 00:05 / --:--  stream.oga", JavaSoundPlaybackDemo.renderProgress(unknown));
    }

    private static AudioFormat pcmFormat(Map<String, Object> properties) {
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

    private static byte[] sequentialBytes(int size) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < size; index++) {
            bytes[index] = (byte) index;
        }
        return bytes;
    }
}
