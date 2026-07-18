package org.zzvsjs.jflac.examples;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class PlaybackControllerTest {
    @Test
    public void openForwardsInitialPositionAndPausedStateWithoutAudioHardware() throws Exception {
        FakeFactory factory = new FakeFactory();
        try (PlaybackController controller = new PlaybackController(factory)) {
            PlaybackViewState view = controller.open(Path.of("music.flac"), 12_500_000L, true);

            FakeSession session = factory.sessions.get(0);
            assertEquals(12_500_000L, session.startedAtMicros);
            assertTrue(session.startedPaused);
            assertEquals(PlaybackSession.State.PAUSED, view.state());
            assertTrue(view.sessionOpen());
        }
    }

    @Test
    public void replacementClosesTheOldSessionBeforeOpeningTheNewOne() throws Exception {
        List<String> events = new ArrayList<>();
        FakeFactory factory = new FakeFactory(events);
        try (PlaybackController controller = new PlaybackController(factory)) {
            controller.open(Path.of("first.flac"), 0L, false);
            controller.open(Path.of("second.flac"), 0L, false);
        }

        assertEquals(
                List.of(
                        "open:first.flac",
                        "start:first.flac",
                        "close:first.flac",
                        "open:second.flac",
                        "start:second.flac",
                        "close:second.flac"
                ),
                events
        );
    }

    @Test
    public void playPauseSeekStopAndReplayUseTheNarrowSessionBoundary() throws Exception {
        FakeFactory factory = new FakeFactory();
        try (PlaybackController controller = new PlaybackController(factory)) {
            controller.open(Path.of("music.flac"), 0L, false);
            FakeSession first = factory.sessions.get(0);

            assertEquals(PlaybackSession.State.PAUSED, controller.togglePlayPause().state());
            assertEquals(1, first.toggleCount);

            controller.seekToMicros(40_000_000L);
            controller.seekByMicros(-10_000_000L);
            assertEquals(List.of(40_000_000L), first.absoluteSeeks);
            assertEquals(List.of(-10_000_000L), first.relativeSeeks);

            PlaybackViewState stopped = controller.stop();
            assertFalse(stopped.sessionOpen());
            assertEquals(Path.of("music.flac"), stopped.input());

            PlaybackViewState restarted = controller.togglePlayPause();
            assertTrue(restarted.sessionOpen());
            assertEquals(2, factory.sessions.size());
            assertEquals(0L, factory.sessions.get(1).startedAtMicros);
        }
    }

    @Test
    public void endedTrackIsReopenedWhenPlayIsRequested() throws Exception {
        FakeFactory factory = new FakeFactory();
        try (PlaybackController controller = new PlaybackController(factory)) {
            controller.open(Path.of("music.flac"), 0L, false);
            factory.sessions.get(0).state = PlaybackSession.State.ENDED;

            PlaybackViewState replayed = controller.togglePlayPause();

            assertEquals(2, factory.sessions.size());
            assertTrue(factory.sessions.get(0).closed);
            assertEquals(PlaybackSession.State.PLAYING, replayed.state());
        }
    }

    @Test
    public void inactiveControllerRejectsSeekAndCloseIsIdempotent() {
        FakeFactory factory = new FakeFactory();
        PlaybackController controller = new PlaybackController(factory);

        assertThrows(IllegalStateException.class, () -> controller.seekToMicros(1L));
        controller.close();
        controller.close();
        assertThrows(IllegalStateException.class, controller::viewState);
    }

    @Test
    public void unavailableMixerLeavesARecoverableStoppedSelection() {
        PlaybackController controller = new PlaybackController(input -> {
            throw new LineUnavailableException("No output mixer");
        });
        try {
            assertThrows(
                    LineUnavailableException.class,
                    () -> controller.open(Path.of("music.flac"), 0L, false)
            );

            PlaybackViewState view = controller.viewState();
            assertFalse(view.sessionOpen());
            assertEquals(Path.of("music.flac"), view.input());
        } finally {
            controller.close();
        }
    }

    private static final class FakeFactory implements PlaybackSessionFactory {
        private final List<FakeSession> sessions = new ArrayList<>();
        private final List<String> events;

        private FakeFactory() {
            this(new ArrayList<>());
        }

        private FakeFactory(List<String> events) {
            this.events = events;
        }

        @Override
        public PlaybackSession open(Path input) {
            events.add("open:" + input);
            FakeSession session = new FakeSession(input, events);
            sessions.add(session);
            return session;
        }
    }

    private static final class FakeSession implements PlaybackSession {
        private static final AudioFormat FORMAT = new AudioFormat(44_100f, 16, 2, true, false);

        private final Path input;
        private final List<String> events;
        private final List<Long> absoluteSeeks = new ArrayList<>();
        private final List<Long> relativeSeeks = new ArrayList<>();

        private State state = State.NEW;
        private long positionMicros;
        private long startedAtMicros;
        private boolean startedPaused;
        private int toggleCount;
        private boolean closed;

        private FakeSession(Path input, List<String> events) {
            this.input = input;
            this.events = events;
        }

        @Override
        public Path input() {
            return input;
        }

        @Override
        public AudioFormat sourceFormat() {
            return FORMAT;
        }

        @Override
        public AudioFormat playbackFormat() {
            return FORMAT;
        }

        @Override
        public long totalSourceFrames() {
            return 5_292_000L;
        }

        @Override
        public void start(long startMicros, boolean paused) {
            events.add("start:" + input);
            startedAtMicros = startMicros;
            startedPaused = paused;
            positionMicros = startMicros;
            state = paused ? State.PAUSED : State.PLAYING;
        }

        @Override
        public void togglePause() {
            toggleCount++;
            state = state == State.PAUSED ? State.PLAYING : State.PAUSED;
        }

        @Override
        public void pause() {
            state = State.PAUSED;
        }

        @Override
        public void resume() {
            state = State.PLAYING;
        }

        @Override
        public boolean isPauseRequested() {
            return state == State.PAUSED;
        }

        @Override
        public void seekToMicros(long targetMicros) {
            absoluteSeeks.add(targetMicros);
            positionMicros = targetMicros;
        }

        @Override
        public void seekByMicros(long deltaMicros) {
            relativeSeeks.add(deltaMicros);
            positionMicros += deltaMicros;
        }

        @Override
        public Snapshot snapshot() {
            return new Snapshot(input, state, positionMicros, 120_000_000L, null);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            state = State.CLOSED;
            events.add("close:" + input);
        }
    }
}
