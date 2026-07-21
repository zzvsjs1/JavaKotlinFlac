package org.zzvsjs.jflac.examples.playback;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class PlaybackViewStateTest {
    private static final AudioFormat FORMAT = new AudioFormat(44_100f, 16, 2, true, false);

    @Test
    public void loadingPausedTrackOffersPlayRatherThanPause() {
        PlaybackViewState loadingPaused = view(
                PlaybackSession.State.LOADING,
                true
        );
        PlaybackViewState loadingAndPlaying = view(
                PlaybackSession.State.LOADING,
                false
        );
        PlaybackViewState ended = view(PlaybackSession.State.ENDED, false);

        assertEquals("Play", loadingPaused.playPauseLabel());
        assertEquals("Pause", loadingAndPlaying.playPauseLabel());
        assertEquals("Play", ended.playPauseLabel());
    }

    private static PlaybackViewState view(
            PlaybackSession.State state,
            boolean pauseRequested
    ) {
        return new PlaybackViewState(
                true,
                Path.of("music.flac"),
                state,
                0L,
                1_000_000L,
                FORMAT,
                FORMAT,
                pauseRequested,
                null
        );
    }
}
