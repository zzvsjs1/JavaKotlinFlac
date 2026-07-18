package org.zzvsjs.jflac.examples;

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
                null
        );
    }

    boolean canControl() {
        return sessionOpen
                && state != PlaybackSession.State.NEW
                && state != PlaybackSession.State.ENDED
                && state != PlaybackSession.State.FAILED
                && state != PlaybackSession.State.CLOSED;
    }

    boolean canSeek() {
        return canControl() && durationMicros > 0L;
    }
}

