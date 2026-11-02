package org.zzvsjs.jflac.examples.playback;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Control;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the production worker with only its external source and audio device replaced. */
public final class JavaSoundPlaybackWorkerTest {
    private static final AudioFormat FORMAT = new AudioFormat(1_000f, 16, 1, true, false);
    private static final long TIMEOUT_SECONDS = 3L;

    @Test
    public void initialAndMidPlaybackPauseResumeThenNaturalEofDrainsBeforeEnding() throws Exception {
        Source source = new Source();
        try (Harness harness = new Harness(source)) {
            harness.session.start(0L, true);
            take(harness.output.stops);
            assertEquals(PlaybackSession.State.PAUSED, harness.session.snapshot().state());
            assertTrue(source.reads.isEmpty());
            assertFalse(harness.output.running);

            harness.session.resume();
            take(source.reads);
            source.actions.add(8);
            assertArrayEquals(bytes(0, 8), take(harness.output.writes));
            take(source.reads);

            // Pause is acknowledged after the already-started read/write finishes.
            harness.session.pause();
            source.actions.add(2);
            assertArrayEquals(bytes(8, 2), take(harness.output.writes));
            take(harness.output.stops);
            assertEquals(PlaybackSession.State.PAUSED, harness.session.snapshot().state());
            assertFalse(harness.output.running);
            assertTrue(harness.output.writes.isEmpty());

            harness.session.resume();
            take(source.reads);
            source.actions.add(-1);
            await(harness.output.drainEntered);
            assertEquals(PlaybackSession.State.DRAINING, harness.session.snapshot().state());
            assertFalse(source.closed.get());
            assertTrue(harness.output.open);

            harness.output.drainRelease.countDown();
            harness.awaitWorkerExit();
            assertEquals(PlaybackSession.State.ENDED, harness.session.snapshot().state());
            assertEquals(100_000L, harness.session.snapshot().positionMicros());
            assertNull(harness.session.snapshot().failure());
            assertTrue(source.closed.get());
            assertFalse(harness.output.open);
        }
    }

    @Test
    public void seekWhilePausedClosesOldSourceAndDiscardsExactWholeFrames() throws Exception {
        Source first = new Source();
        Source reopened = new Source();
        try (Harness harness = new Harness(first, reopened)) {
            harness.session.start(0L, true);
            take(harness.output.stops);
            harness.session.seekToMicros(6_000L);
            take(harness.output.stops); // Seek stops/flushed the obsolete output.
            take(harness.output.stops); // Reopened input is now paused.

            assertTrue(first.closed.get());
            assertEquals(12L, reopened.bytePosition);
            assertEquals(6_000L, harness.session.snapshot().positionMicros());
            assertEquals(PlaybackSession.State.PAUSED, harness.session.snapshot().state());
            assertTrue(reopened.reads.isEmpty());
            assertTrue(harness.output.writes.isEmpty());
            assertTrue(harness.output.flushCount > 0);

            harness.session.resume();
            take(reopened.reads);
            reopened.actions.add(4);
            assertArrayEquals(bytes(12, 4), take(harness.output.writes));
        }
    }

    @Test
    public void aNewSeekSupersedesAnInFlightDiscardAndKeepsLatestPausedPosition() throws Exception {
        Source initial = new Source();
        Source superseded = new Source();
        superseded.gateSkip = true;
        Source latest = new Source();
        try (Harness harness = new Harness(initial, superseded, latest)) {
            harness.session.start(0L, true);
            take(harness.output.stops);
            harness.session.seekToMicros(50_000L);
            take(harness.output.stops);
            await(superseded.skipEntered);

            harness.session.seekToMicros(3_000L);
            superseded.skipRelease.countDown();
            take(harness.output.stops);
            assertTrue(initial.closed.get());
            assertTrue(superseded.closed.get());
            assertEquals(6L, latest.bytePosition);
            assertEquals(3_000L, harness.session.snapshot().positionMicros());
            assertEquals(PlaybackSession.State.PAUSED, harness.session.snapshot().state());
            assertTrue(harness.output.writes.isEmpty());
        }
    }

    @Test
    public void closeCancelsAReadThatOnlySourceClosureCanRelease() throws Exception {
        Source source = new Source();
        try (Harness harness = new Harness(source)) {
            harness.session.start(0L, false);
            take(source.reads);

            harness.session.close();
            harness.awaitWorkerExit();

            assertTrue(source.closed.get());
            assertFalse(harness.output.open);
            assertEquals(PlaybackSession.State.CLOSED, harness.session.snapshot().state());
            assertNull(harness.session.snapshot().failure());
            assertEquals(1L, harness.output.drainEntered.getCount());
        }
    }

    @Test
    public void readFailureStillClosesOutputAndPublishesOriginalFailure() throws Exception {
        Source source = new Source();
        source.readFailure = new IOException("decoder read failure");
        try (Harness harness = new Harness(source)) {
            harness.session.start(0L, false);
            take(source.reads);
            source.actions.add(2);
            harness.awaitWorkerExit();

            assertEquals(PlaybackSession.State.FAILED, harness.session.snapshot().state());
            assertSame(source.readFailure, harness.session.snapshot().failure());
            assertTrue(source.closed.get());
            assertFalse(harness.output.open);
            assertEquals(1L, harness.output.drainEntered.getCount());
        }
    }

    @Test
    public void outputWriteFailureStillClosesSourceAndPublishesOriginalFailure() throws Exception {
        Source source = new Source();
        try (Harness harness = new Harness(source)) {
            harness.output.writeFailure = new IllegalStateException("mixer write failure");
            harness.session.start(0L, false);
            take(source.reads);
            source.actions.add(4);
            harness.awaitWorkerExit();

            assertEquals(PlaybackSession.State.FAILED, harness.session.snapshot().state());
            assertSame(harness.output.writeFailure, harness.session.snapshot().failure());
            assertTrue(source.closed.get());
            assertFalse(harness.output.open);
        }
    }

    @Test
    public void sourceCloseFailureAtEofPreventsSuccessfulEndedState() throws Exception {
        Source source = new Source();
        source.closeFailure = new IOException("decoder close failure");
        try (Harness harness = new Harness(source)) {
            harness.session.start(0L, false);
            take(source.reads);
            source.actions.add(-1);
            await(harness.output.drainEntered);
            harness.output.drainRelease.countDown();
            harness.awaitWorkerExit();

            assertEquals(PlaybackSession.State.FAILED, harness.session.snapshot().state());
            assertSame(source.closeFailure, harness.session.snapshot().failure());
            assertFalse(harness.output.open);
        }
    }

    @Test
    public void cancellationReportsSourceCloseFailureAfterReleasingTheWorker() throws Exception {
        Source source = new Source();
        source.closeFailure = new IOException("cancel source close failure");
        try (Harness harness = new Harness(source)) {
            harness.session.start(0L, false);
            take(source.reads);
            IllegalStateException failure = assertThrows(IllegalStateException.class, harness.session::close);
            harness.awaitWorkerExit();

            assertSame(source.closeFailure, failure.getCause());
            assertEquals(PlaybackSession.State.CLOSED, harness.session.snapshot().state());
            assertNotNull(harness.session.snapshot().failure());
            assertFalse(harness.output.open);
        }
    }

    @Test
    public void supersededSeekMustNotDiscardSourceCleanupFailure() throws Exception {
        Source initial = new Source();
        Source superseded = new Source();
        superseded.gateSkip = true;
        superseded.closeFailure = new IOException("superseded decoder close failure");
        Source latest = new Source();
        latest.actions.add(-1);
        try (Harness harness = new Harness(initial, superseded, latest)) {
            harness.output.drainRelease.countDown();
            harness.session.start(0L, true);
            take(harness.output.stops);
            harness.session.seekToMicros(50_000L);
            take(harness.output.stops);
            await(superseded.skipEntered);

            harness.session.seekToMicros(3_000L);
            harness.session.resume();
            superseded.skipRelease.countDown();
            harness.awaitWorkerExit();

            // Even if the newer source reaches EOF, failed decoder release is
            // a playback failure rather than a successful superseded command.
            assertEquals(PlaybackSession.State.FAILED, harness.session.snapshot().state());
            assertTrue(containsFailure(harness.session.snapshot().failure(), superseded.closeFailure));
            assertFalse(harness.output.open);
        }
    }

    @Test
    public void requestedCloseRetainsWorkerMixerCleanupErrorsWithoutCancellationReadError() throws Exception {
        Source source = new Source();
        try (Harness harness = new Harness(source)) {
            harness.output.gateCallerStop = true;
            harness.output.workerStopFailure = new IllegalStateException("worker mixer stop failure");
            harness.output.workerCloseFailure = new IllegalStateException("worker mixer release failure");
            harness.session.start(0L, false);
            take(source.reads);

            ExecutorService closer = Executors.newSingleThreadExecutor();
            try {
                Future<?> closing = closer.submit(harness.session::close);
                await(harness.output.callerStopEntered);
                // closeRequested is already published, but caller mixer cleanup
                // is gated. The worker must retain its own failed release even
                // when the caller's later duplicate close returns successfully.
                source.actions.add(-1);
                harness.awaitWorkerExit();
                harness.output.callerStopRelease.countDown();

                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> closing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                assertSame(harness.output.workerStopFailure, failure.getCause());
                assertTrue(containsFailure(failure, harness.output.workerCloseFailure));
                assertEquals(PlaybackSession.State.CLOSED, harness.session.snapshot().state());
                assertFalse(harness.output.open);
            } finally {
                harness.output.callerStopRelease.countDown();
                closer.shutdownNow();
                assertTrue(closer.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void closeDuringSeekRetainsSourceCloseFailureBeforeAsyncCancellation() throws Exception {
        Source initial = new Source();
        Source seeking = new Source();
        seeking.gateSkip = true;
        seeking.closeFailure = new IOException("cancelled seek decoder close failure");
        try (Harness harness = new Harness(initial, seeking)) {
            harness.output.gateCallerStop = true;
            harness.session.start(0L, true);
            take(harness.output.stops);
            harness.session.seekToMicros(50_000L);
            take(harness.output.stops);
            await(seeking.skipEntered);

            ExecutorService closer = Executors.newSingleThreadExecutor();
            try {
                Future<?> closing = closer.submit(harness.session::close);
                await(harness.output.callerStopEntered);
                // The worker notices closeRequested while discarding frames,
                // then attempts source cleanup before asynchronous cancellation
                // can acquire that source. Its cleanup error must still survive.
                seeking.skipRelease.countDown();
                harness.awaitWorkerExit();
                harness.output.callerStopRelease.countDown();

                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> closing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                assertTrue(containsFailure(failure, seeking.closeFailure));
                assertEquals(PlaybackSession.State.CLOSED, harness.session.snapshot().state());
                assertTrue(seeking.closed.get());
                assertFalse(harness.output.open);
            } finally {
                harness.output.callerStopRelease.countDown();
                closer.shutdownNow();
                assertTrue(closer.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void closeDuringOldSourceSeekReleaseRetainsFirstCloseFailureWhenRepeatedCloseSucceeds() throws Exception {
        Source initial = new Source();
        initial.gateClose = true;
        initial.closeFailure = new IOException("old seek source release failure");
        try (Harness harness = new Harness(initial)) {
            harness.output.gateCallerStop = true;
            harness.session.start(0L, true);
            take(harness.output.stops);
            harness.session.seekToMicros(50_000L);
            await(initial.closeEntered);

            ExecutorService closer = Executors.newSingleThreadExecutor();
            try {
                Future<?> closing = closer.submit(harness.session::close);
                await(harness.output.callerStopEntered);
                // Stop has published closeRequested while the worker still owns
                // the first release. Its final duplicate close will succeed.
                initial.closeRelease.countDown();
                harness.awaitWorkerExit();
                await(initial.repeatedCloseReturned);
                harness.output.callerStopRelease.countDown();

                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> closing.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                assertSame(initial.closeFailure, failure.getCause().getCause());
                assertSame(initial.closeFailure, harness.session.snapshot().failure());
                assertEquals(PlaybackSession.State.CLOSED, harness.session.snapshot().state());
                assertTrue(initial.closed.get());
                assertFalse(harness.output.open);
            } finally {
                initial.closeRelease.countDown();
                harness.output.callerStopRelease.countDown();
                closer.shutdownNow();
                assertTrue(closer.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
        }
    }

    private static boolean containsFailure(Throwable actual, Throwable expected) {
        if (actual == null) {
            return false;
        }
        if (actual == expected || containsFailure(actual.getCause(), expected)) {
            return true;
        }
        for (Throwable suppressed : actual.getSuppressed()) {
            if (containsFailure(suppressed, expected)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] bytes(int start, int size) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < size; index++) {
            bytes[index] = (byte) (start + index);
        }
        return bytes;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Worker did not reach the expected boundary");
    }

    private static <T> T take(BlockingQueue<T> queue) throws InterruptedException {
        T value = queue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(value, "Worker did not reach the expected boundary");
        return value;
    }

    private static final class Harness implements AutoCloseable {
        private final Output output = new Output();
        private final Source[] allSources;
        private final CompletableFuture<Thread> worker = new CompletableFuture<>();
        private final JavaSoundPlaybackSession session;

        private Harness(Source... sources) {
            allSources = sources;
            Queue<Source> remaining = new ArrayDeque<>(Arrays.asList(sources));
            session = new JavaSoundPlaybackSession(Path.of("memory.flac"), FORMAT, FORMAT, 100L, output, () -> {
                output.workerThread = Thread.currentThread();
                worker.complete(Thread.currentThread());
                Source source = remaining.poll();
                if (source == null) {
                    throw new IOException("Unexpected extra source open");
                }
                return source;
            });
        }

        private void awaitWorkerExit() throws Exception {
            Thread thread = worker.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            assertFalse(thread.isAlive(), "Playback worker did not terminate");
        }

        @Override
        public void close() {
            // Release every possible external gate even if an assertion failed.
            output.drainRelease.countDown();
            output.callerStopRelease.countDown();
            for (Source source : allSources) {
                source.skipRelease.countDown();
                source.closeRelease.countDown();
            }
            session.close();
        }
    }

    private static final class Source extends AudioInputStream {
        private final BlockingQueue<Integer> actions = new LinkedBlockingQueue<>();
        private final BlockingQueue<Boolean> reads = new LinkedBlockingQueue<>();
        private final CountDownLatch skipEntered = new CountDownLatch(1);
        private final CountDownLatch skipRelease = new CountDownLatch(1);
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch closeRelease = new CountDownLatch(1);
        private final CountDownLatch repeatedCloseReturned = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile long bytePosition;
        private boolean gateSkip;
        private boolean gateClose;
        private IOException readFailure;
        private IOException closeFailure;

        private Source() {
            super(new ByteArrayInputStream(new byte[0]), FORMAT, AudioSystem.NOT_SPECIFIED);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            reads.add(true);
            int count;
            // Model a provider whose native read ignores thread interruption;
            // asynchronous close must release it. The bounded wait diagnoses leaks.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (true) {
                try {
                    Integer action = actions.poll(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    if (action == null) {
                        throw new IOException("Test source read was not released");
                    }
                    count = action;
                    break;
                } catch (InterruptedException ignored) {
                    // Deliberately require source closure to cancel this read.
                }
            }
            if (closed.get()) {
                throw new IOException("Source cancelled");
            }
            if (readFailure != null) {
                throw readFailure;
            }
            if (count == -1) {
                return -1;
            }
            count = Math.min(count, length);
            System.arraycopy(bytes((int) bytePosition, count), 0, buffer, offset, count);
            bytePosition += count;
            return count;
        }

        @Override
        public long skip(long count) throws IOException {
            if (gateSkip) {
                skipEntered.countDown();
                try {
                    if (!skipRelease.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IOException("Test seek was not released");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Test seek interrupted", error);
                }
            }
            bytePosition += count;
            return count;
        }

        @Override
        public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                actions.offer(-1);
                skipRelease.countDown();
                if (gateClose) {
                    closeEntered.countDown();
                    try {
                        if (!closeRelease.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            throw new IOException("Test source close was not released");
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Test source close interrupted", error);
                    }
                }
                if (closeFailure != null) {
                    throw closeFailure;
                }
            } else {
                repeatedCloseReturned.countDown();
            }
        }
    }

    private static final class Output implements SourceDataLine {
        private final BlockingQueue<Boolean> stops = new LinkedBlockingQueue<>();
        private final BlockingQueue<byte[]> writes = new LinkedBlockingQueue<>();
        private final CountDownLatch drainEntered = new CountDownLatch(1);
        private final CountDownLatch drainRelease = new CountDownLatch(1);
        private final CountDownLatch callerStopEntered = new CountDownLatch(1);
        private final CountDownLatch callerStopRelease = new CountDownLatch(1);
        private volatile boolean open = true;
        private volatile boolean running;
        private volatile int flushCount;
        private RuntimeException writeFailure;
        private volatile Thread workerThread;
        private boolean gateCallerStop;
        private RuntimeException workerStopFailure;
        private RuntimeException workerCloseFailure;

        @Override
        public int write(byte[] buffer, int offset, int length) {
            if (writeFailure != null) {
                throw writeFailure;
            }
            writes.add(Arrays.copyOfRange(buffer, offset, offset + length));
            return length;
        }

        @Override
        public void drain() {
            drainEntered.countDown();
            try {
                if (!drainRelease.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test drain was not released");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }

        @Override public void start() { running = true; }
        @Override
        public void stop() {
            if (gateCallerStop && Thread.currentThread() != workerThread) {
                callerStopEntered.countDown();
                try {
                    if (!callerStopRelease.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Caller mixer stop was not released");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Caller mixer stop interrupted", error);
                }
            }
            if (Thread.currentThread() == workerThread && workerStopFailure != null) {
                throw workerStopFailure;
            }
            running = false;
            stops.add(true);
        }
        @Override public void flush() { flushCount++; }
        @Override
        public void close() {
            boolean wasOpen = open;
            open = false;
            running = false;
            drainRelease.countDown();
            if (wasOpen && Thread.currentThread() == workerThread && workerCloseFailure != null) {
                throw workerCloseFailure;
            }
        }
        @Override public boolean isOpen() { return open; }
        @Override public boolean isRunning() { return running; }
        @Override public boolean isActive() { return running; }
        @Override public AudioFormat getFormat() { return FORMAT; }
        @Override public int getBufferSize() { return 1024; }
        @Override public int available() { return getBufferSize(); }
        @Override public int getFramePosition() { return 0; }
        @Override public long getLongFramePosition() { return 0L; }
        @Override public long getMicrosecondPosition() { return 0L; }
        @Override public float getLevel() { return 0f; }
        @Override public Line.Info getLineInfo() { return new DataLine.Info(SourceDataLine.class, FORMAT); }
        @Override public Control[] getControls() { return new Control[0]; }
        @Override public boolean isControlSupported(Control.Type type) { return false; }
        @Override public Control getControl(Control.Type type) { throw new IllegalArgumentException("Unsupported control"); }
        @Override public void addLineListener(LineListener listener) { }
        @Override public void removeLineListener(LineListener listener) { }
        @Override public void open() { open = true; }
        @Override public void open(AudioFormat format) { open = true; }
        @Override public void open(AudioFormat format, int bufferSize) { open = true; }
    }
}
