package org.zzvsjs.jflac.examples.playback;

import javax.sound.sampled.AudioFormat;
import java.nio.file.Path;

/**
 * Narrow control boundary shared by the console and terminal user interfaces.
 *
 * <p>The interface deliberately contains no terminal or Java Sound device
 * types beyond the immutable audio formats. User-interface tests can provide a
 * deterministic fake without opening a mixer or starting a decoding thread.</p>
 */
interface PlaybackSession extends AutoCloseable {
    enum State {
        NEW,
        LOADING,
        PLAYING,
        PAUSED,
        DRAINING,
        ENDED,
        FAILED,
        CLOSED;

        boolean isTerminal() {
            return this == ENDED || this == FAILED || this == CLOSED;
        }
    }

    record Snapshot(
            Path input,
            State state,
            long positionMicros,
            long durationMicros,
            Throwable failure
    ) {
    }

    Path input();

    AudioFormat sourceFormat();

    AudioFormat playbackFormat();

    void start(long startMicros, boolean paused);

    void togglePause();

    void pause();

    void resume();

    boolean isPauseRequested();

    void seekToMicros(long targetMicros);

    void seekByMicros(long deltaMicros);

    Snapshot snapshot();

    @Override
    void close();
}
