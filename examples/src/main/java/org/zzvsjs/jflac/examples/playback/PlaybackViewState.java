package org.zzvsjs.jflac.examples.playback;

import javax.sound.sampled.AudioFormat;
import java.nio.file.Path;

/** Immutable data rendered by either a real terminal UI or a test view. */
record PlaybackViewState(
        boolean sessionOpen,
        Path input,
        PlaybackSession.State state,
        long positionMicros,
        long durationMicros,
        AudioFormat sourceFormat,
        AudioFormat playbackFormat,
        boolean pauseRequested,
        Throwable failure
) {
    static PlaybackViewState idle(Path selectedInput) {
        return new PlaybackViewState(
                false,
                selectedInput,
                PlaybackSession.State.CLOSED,
                0L,
                -1L,
                null,
                null,
                false,
                null
        );
    }

    boolean canControl() {
        return sessionOpen
                && state != PlaybackSession.State.NEW
                && !state.isTerminal();
    }

    boolean canSeek() {
        return canControl() && durationMicros > 0L;
    }

    String playPauseLabel() {
        /*
         * LOADING does not reveal whether a seek/open was requested in a
         * paused state. Use the requested pause flag so the button describes
         * the action it will actually perform: "Play" resumes, while "Pause"
         * requests a pause.
         */
        return canControl() && !pauseRequested ? "Pause" : "Play";
    }
}
