package org.zzvsjs.jflac.examples;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Device-independent playback controller used by the terminal UI.
 *
 * <p>All methods are intended to be called by one UI thread. The underlying
 * session may decode on its own worker, but exposes progress through immutable
 * snapshots. This makes the complete control policy testable with a fake
 * session and no sound device.</p>
 */
final class PlaybackController implements AutoCloseable {
    private final PlaybackSessionFactory sessionFactory;

    private PlaybackSession session;
    private Path selectedInput;
    private boolean closed;

    PlaybackController(PlaybackSessionFactory sessionFactory) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    }

    PlaybackViewState open(Path input, long startMicros, boolean paused) throws Exception {
        ensureOpen();
        Objects.requireNonNull(input, "input");
        if (startMicros < 0L) {
            throw new IllegalArgumentException("Playback start time must be non-negative.");
        }

        // An output device can normally serve only one session at a time. Close
        // the old session before asking the factory to acquire the next line.
        closeCurrentSession();
        selectedInput = input.normalize();

        PlaybackSession opened = Objects.requireNonNull(
                sessionFactory.open(selectedInput),
                "sessionFactory returned null"
        );
        try {
            opened.start(startMicros, paused);
            selectedInput = Objects.requireNonNull(opened.input(), "session input");
            session = opened;
            return viewState();
        } catch (RuntimeException | Error failure) {
            if (session == opened) {
                session = null;
            }
            try {
                opened.close();
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    PlaybackViewState togglePlayPause() throws Exception {
        ensureOpen();
        if (session == null) {
            if (selectedInput == null) {
                throw new IllegalStateException("Select a music file before starting playback.");
            }
            return open(selectedInput, 0L, false);
        }

        PlaybackSession.State state = session.snapshot().state();
        if (state == PlaybackSession.State.ENDED
                || state == PlaybackSession.State.FAILED
                || state == PlaybackSession.State.CLOSED) {
            return open(selectedInput, 0L, false);
        }

        session.togglePause();
        return viewState();
    }

    PlaybackViewState seekToMicros(long targetMicros) {
        requireControllableSession().seekToMicros(Math.max(0L, targetMicros));
        return viewState();
    }

    PlaybackViewState seekByMicros(long deltaMicros) {
        requireControllableSession().seekByMicros(deltaMicros);
        return viewState();
    }

    PlaybackViewState stop() {
        ensureOpen();
        closeCurrentSession();
        return viewState();
    }

    PlaybackViewState viewState() {
        ensureOpen();
        if (session == null) {
            return PlaybackViewState.idle(selectedInput);
        }

        PlaybackSession.Snapshot snapshot = session.snapshot();
        return new PlaybackViewState(
                true,
                snapshot.input(),
                snapshot.state(),
                snapshot.positionMicros(),
                snapshot.durationMicros(),
                session.sourceFormat(),
                session.playbackFormat(),
                snapshot.failure()
        );
    }

    private PlaybackSession requireControllableSession() {
        PlaybackViewState view = viewState();
        if (!view.canControl()) {
            throw new IllegalStateException("Playback is not currently controllable.");
        }
        return session;
    }

    private void closeCurrentSession() {
        PlaybackSession previous = session;
        session = null;
        if (previous != null) {
            previous.close();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Playback controller is closed.");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeCurrentSession();
    }
}
