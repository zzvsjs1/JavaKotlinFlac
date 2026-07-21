package org.zzvsjs.jflac.examples.playback;

import org.zzvsjs.jflac.sound.JflacAudioFileProperties;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * One controllable Java Sound playback session.
 *
 * <p>The worker thread exclusively owns the decoded stream and output writes.
 * User-interface controls only change requested state under {@link #monitor}; this
 * avoids closing an AudioInputStream while the Java Sound converter is reading
 * from it.</p>
 */
final class JavaSoundPlaybackSession implements PlaybackSession {
    private static final int PREFERRED_BUFFER_BYTES = 16 * 1024;
    private static final int DISCARD_BUFFER_BYTES = 8 * 1024;
    private static final long CLOSE_JOIN_MILLIS = 5_000L;

    private final Object monitor = new Object();
    private final Path input;
    private final AudioFormat sourceFormat;
    private final AudioFormat playbackFormat;
    private final long totalSourceFrames;
    private final long durationMicros;
    private final SourceDataLine line;

    private Thread worker;
    private State state = State.NEW;
    private boolean pauseRequested;
    private boolean closeRequested;
    private Long pendingSeekFrame;
    private long seekRevision;
    private long segmentStartSourceFrame;
    private long segmentWrittenOutputFrames;
    private long lastKnownPositionMicros;
    private Throwable failure;
    private Throwable shutdownCleanupFailure;
    private volatile AudioInputStream cancellableSource;

    JavaSoundPlaybackSession(Path input) throws Exception {
        this.input = PlaybackFiles.validateInputPath(input);

        TrackDetails details = inspectTrack(this.input);
        this.sourceFormat = details.sourceFormat();
        this.playbackFormat = details.playbackFormat();
        this.totalSourceFrames = details.totalSourceFrames();
        this.durationMicros = totalSourceFrames < 0L
                ? -1L
                : framesToMicros(totalSourceFrames, sourceFormat.getSampleRate());
        this.line = PlaybackAudioOutput.openPlaybackLine(playbackFormat);
    }

    @Override
    public Path input() {
        return input;
    }

    @Override
    public AudioFormat sourceFormat() {
        return sourceFormat;
    }

    @Override
    public AudioFormat playbackFormat() {
        return playbackFormat;
    }

    @Override
    public void start(long startMicros, boolean paused) {
        if (startMicros < 0L) {
            throw new IllegalArgumentException("Playback start time must be non-negative.");
        }

        synchronized (monitor) {
            if (state != State.NEW) {
                throw new IllegalStateException("Playback session has already been started.");
            }

            pauseRequested = paused;
            pendingSeekFrame = clampSourceFrame(microsToFrames(startMicros, sourceFormat.getSampleRate()));
            seekRevision = Math.addExact(seekRevision, 1L);
            state = State.LOADING;
            worker = new Thread(this::runPlayback, "jflac-java-sound-playback");
            worker.setDaemon(true);
            worker.start();
        }
    }

    @Override
    public void togglePause() {
        synchronized (monitor) {
            ensureControllable();
            pauseRequested = !pauseRequested;
            monitor.notifyAll();
        }
    }

    @Override
    public void pause() {
        synchronized (monitor) {
            ensureControllable();
            pauseRequested = true;
            monitor.notifyAll();
        }
    }

    @Override
    public void resume() {
        synchronized (monitor) {
            ensureControllable();
            pauseRequested = false;
            monitor.notifyAll();
        }
    }

    @Override
    public boolean isPauseRequested() {
        synchronized (monitor) {
            return pauseRequested;
        }
    }

    @Override
    public void seekToMicros(long targetMicros) {
        if (targetMicros < 0L) {
            throw new IllegalArgumentException("Seek target must be non-negative.");
        }

        synchronized (monitor) {
            ensureControllable();
            pendingSeekFrame = clampSourceFrame(microsToFrames(targetMicros, sourceFormat.getSampleRate()));
            seekRevision = Math.addExact(seekRevision, 1L);
            state = State.LOADING;
            monitor.notifyAll();
        }
    }

    @Override
    public void seekByMicros(long deltaMicros) {
        long current = snapshot().positionMicros();
        long target;
        if (deltaMicros > 0L && current > Long.MAX_VALUE - deltaMicros) {
            target = Long.MAX_VALUE;
        } else if (deltaMicros == Long.MIN_VALUE || (deltaMicros < 0L && current < -deltaMicros)) {
            target = 0L;
        } else {
            target = current + deltaMicros;
        }

        seekToMicros(Math.max(0L, target));
    }

    @Override
    public Snapshot snapshot() {
        PositionState positionState;
        State currentState;
        Throwable currentFailure;
        synchronized (monitor) {
            currentState = state;
            currentFailure = failure;
            positionState = new PositionState(
                    state,
                    segmentStartSourceFrame,
                    segmentWrittenOutputFrames,
                    lastKnownPositionMicros
            );
        }

        /*
         * Mixer implementations are external code and may block. Query the
         * output line after copying monitor-protected bookkeeping so a slow
         * provider cannot prevent pause, seek, or close from taking the lock.
         */
        long position = calculatePositionMicros(positionState);
        return new Snapshot(input, currentState, position, durationMicros, currentFailure);
    }

    private void ensureControllable() {
        if (state == State.NEW) {
            throw new IllegalStateException("Playback session has not been started.");
        }

        if (state.isTerminal() || closeRequested) {
            throw new IllegalStateException("Playback session is no longer active.");
        }
    }

    private void runPlayback() {
        PlaybackInput openedInput = null;
        Throwable playbackFailure = null;
        boolean naturalEndReached = false;
        try {
            openedInput = openLatestRequestedInput();
            if (openedInput == null) {
                return;
            }

            int frameSize = playbackFormat.getFrameSize();
            int bufferBytes = alignedBufferSize(PREFERRED_BUFFER_BYTES, frameSize);
            byte[] buffer = new byte[bufferBytes];

            while (true) {
                boolean seekPending;
                boolean paused;
                synchronized (monitor) {
                    if (closeRequested) {
                        return;
                    }

                    seekPending = pendingSeekFrame != null;
                    paused = pauseRequested;
                    if (!seekPending) {
                        state = paused ? State.PAUSED : State.PLAYING;
                    }
                }

                if (seekPending) {
                    line.stop();
                    line.flush();
                    openedInput.close();
                    cancellableSource = null;
                    openedInput = openLatestRequestedInput();
                    if (openedInput == null) {
                        return;
                    }

                    continue;
                }

                if (paused) {
                    line.stop();
                    waitForControlChange();
                    continue;
                }

                if (!line.isRunning()) {
                    line.start();
                }

                int read = openedInput.playbackStream().read(buffer, 0, buffer.length);
                if (read == -1) {
                    /*
                     * Only natural EOF drains queued audio. Seek, open, and
                     * close paths flush instead so a command does not wait for
                     * obsolete PCM to reach the speakers.
                     */
                    boolean waitAtEnd;
                    synchronized (monitor) {
                        if (closeRequested) {
                            return;
                        }

                        if (pendingSeekFrame != null) {
                            continue;
                        }

                        if (pauseRequested) {
                            state = State.PAUSED;
                            waitAtEnd = true;
                        } else {
                            state = State.DRAINING;
                            waitAtEnd = false;
                        }
                    }

                    if (waitAtEnd) {
                        line.stop();
                        waitForControlChange();
                        continue;
                    }

                    line.drain();
                    boolean commandArrived;
                    synchronized (monitor) {
                        if (closeRequested) {
                            return;
                        }

                        commandArrived = pendingSeekFrame != null || pauseRequested;
                        if (!commandArrived) {
                            lastKnownPositionMicros = durationMicros >= 0L
                                    ? durationMicros
                                    : calculateWrittenPositionMicros();
                            naturalEndReached = true;
                        }
                    }
                    if (commandArrived) {
                        continue;
                    }

                    return;
                }

                if (read % frameSize != 0) {
                    throw new IOException("Java Sound returned a partial PCM frame.");
                }

                writeFully(line, buffer, read);
                synchronized (monitor) {
                    segmentWrittenOutputFrames = Math.addExact(
                            segmentWrittenOutputFrames,
                            read / frameSize
                    );
                    lastKnownPositionMicros = calculateWrittenPositionMicros();
                }
            }
        } catch (Exception | IOError | LinkageError error) {
            playbackFailure = error;
        } finally {
            /*
             * Keep input cleanup separate from an expected cancellation read
             * error. During requested shutdown the latter should not make Stop
             * fail, but failure to release the decoder's native session must
             * still be returned to the caller of close().
             */
            Throwable inputCleanupFailure = closePlaybackInput(openedInput, null);
            playbackFailure = mergeFailure(playbackFailure, inputCleanupFailure);
            cancellableSource = null;

            try {
                line.stop();
            } catch (RuntimeException | IOError | LinkageError stopFailure) {
                playbackFailure = mergeFailure(playbackFailure, stopFailure);
            }

            try {
                line.close();
            } catch (RuntimeException | IOError | LinkageError closeFailure) {
                playbackFailure = mergeFailure(playbackFailure, closeFailure);
            }

            synchronized (monitor) {
                if (closeRequested) {
                    shutdownCleanupFailure = mergeFailure(
                            shutdownCleanupFailure,
                            inputCleanupFailure
                    );
                } else {
                    if (playbackFailure != null) {
                        /*
                         * A cleanup-only failure still matters: the decoder or
                         * mixer may retain native resources even though all PCM
                         * was written successfully.
                         */
                        failure = playbackFailure;
                        state = State.FAILED;
                    } else if (naturalEndReached) {
                        /*
                         * Publish ENDED only after decoder and mixer cleanup
                         * succeeds. Frontends may treat ENDED as final success
                         * and immediately close or replace the session.
                         */
                        state = State.ENDED;
                    }
                }
            }
        }
    }

    private void waitForControlChange() throws InterruptedException {
        synchronized (monitor) {
            while (!closeRequested && pauseRequested && pendingSeekFrame == null) {
                monitor.wait();
            }
        }
    }

    private void resetSegment(long actualSourceFrame) {
        synchronized (monitor) {
            segmentStartSourceFrame = actualSourceFrame;
            segmentWrittenOutputFrames = 0L;
            lastKnownPositionMicros = framesToMicros(actualSourceFrame, sourceFormat.getSampleRate());
        }
    }

    /**
     * Opens the newest requested position. A seek issued while an older linear
     * skip is in progress increments seekRevision; the skip checks that token
     * after each small chunk and yields to the newer request.
     */
    private PlaybackInput openLatestRequestedInput() throws Exception {
        while (true) {
            SeekRequest request;
            synchronized (monitor) {
                if (closeRequested) {
                    return null;
                }

                if (pendingSeekFrame == null) {
                    throw new IllegalStateException("Playback worker expected a pending seek request.");
                }

                request = new SeekRequest(pendingSeekFrame, seekRevision);
                pendingSeekFrame = null;
                state = State.LOADING;
            }

            resetSegment(request.sourceFrame());
            try {
                PlaybackInput opened = openPlaybackInput(request.sourceFrame(), request.revision());
                resetSegment(opened.actualSourceFrame());
                return opened;
            } catch (SeekSupersededException ignored) {
                // Loop and consume the newer pending request.
            }
        }
    }

    private PlaybackInput openPlaybackInput(long targetSourceFrame, long revision) throws Exception {
        AudioInputStream source = AudioSystem.getAudioInputStream(input.toFile());
        try {
            boolean closeWasRequested;
            synchronized (monitor) {
                closeWasRequested = closeRequested;
                if (!closeWasRequested) {
                    cancellableSource = source;
                }
            }
            if (closeWasRequested) {
                /*
                 * Source closure can enter provider/native code, so perform it
                 * through the catch cleanup below after releasing the monitor.
                 */
                throw new IOException("Playback was closed while reopening the music file.");
            }

            if (!PlaybackAudioOutput.isSamePcmFormat(source.getFormat(), sourceFormat)) {
                throw new IOException("Decoded PCM format changed while reopening the music file.");
            }

            long actualSourceFrame = skipPcmFrames(
                    source,
                    sourceFormat.getFrameSize(),
                    targetSourceFrame,
                    () -> isSeekSuperseded(revision)
            );
            if (isSeekSuperseded(revision)) {
                throw new SeekSupersededException();
            }

            AudioInputStream playback = source;
            if (!PlaybackAudioOutput.isSamePcmFormat(sourceFormat, playbackFormat)) {
                playback = AudioSystem.getAudioInputStream(playbackFormat, source);
            }

            return new PlaybackInput(source, playback, actualSourceFrame);
        } catch (Exception | IOError | LinkageError error) {
            if (cancellableSource == source) {
                cancellableSource = null;
            }

            try {
                source.close();
            } catch (Exception | IOError | LinkageError closeError) {
                mergeFailure(error, closeError);
            }

            throw error;
        }
    }

    private boolean isSeekSuperseded(long revision) {
        synchronized (monitor) {
            return closeRequested || seekRevision != revision;
        }
    }

    private long calculatePositionMicros(PositionState positionState) {
        if (positionState.state() == State.ENDED && durationMicros >= 0L) {
            return durationMicros;
        }

        if (positionState.state() == State.CLOSED) {
            return clampDuration(positionState.lastKnownPositionMicros());
        }

        long queuedOutputFrames = 0L;
        try {
            if (!line.isOpen()) {
                return clampDuration(positionState.lastKnownPositionMicros());
            }

            int queuedBytes = Math.max(0, line.getBufferSize() - line.available());
            queuedOutputFrames = queuedBytes / playbackFormat.getFrameSize();
        } catch (RuntimeException | IOError | LinkageError ignored) {
            // A concurrent close or faulty mixer falls back to completed writes.
        }

        long audibleOutputFrames = Math.max(
                0L,
                positionState.segmentWrittenOutputFrames() - queuedOutputFrames
        );
        long audibleSourceFrames = convertFrameCount(
                audibleOutputFrames,
                playbackFormat.getSampleRate(),
                sourceFormat.getSampleRate()
        );
        long sourceFrame;
        try {
            sourceFrame = Math.addExact(
                    positionState.segmentStartSourceFrame(),
                    audibleSourceFrames
            );
        } catch (ArithmeticException error) {
            sourceFrame = Long.MAX_VALUE;
        }

        return clampDuration(framesToMicros(sourceFrame, sourceFormat.getSampleRate()));
    }

    private long calculateWrittenPositionMicros() {
        long sourceFrames = convertFrameCount(
                segmentWrittenOutputFrames,
                playbackFormat.getSampleRate(),
                sourceFormat.getSampleRate()
        );
        long absoluteFrame;
        try {
            absoluteFrame = Math.addExact(segmentStartSourceFrame, sourceFrames);
        } catch (ArithmeticException error) {
            absoluteFrame = Long.MAX_VALUE;
        }

        return clampDuration(framesToMicros(absoluteFrame, sourceFormat.getSampleRate()));
    }

    private long clampDuration(long positionMicros) {
        long nonNegative = Math.max(0L, positionMicros);
        return durationMicros < 0L ? nonNegative : Math.min(nonNegative, durationMicros);
    }

    private long clampSourceFrame(long sourceFrame) {
        long nonNegative = Math.max(0L, sourceFrame);
        return totalSourceFrames < 0L ? nonNegative : Math.min(nonNegative, totalSourceFrames);
    }

    @Override
    public void close() {
        Thread thread;
        synchronized (monitor) {
            if (state == State.CLOSED) {
                return;
            }

            closeRequested = true;
            monitor.notifyAll();
            thread = worker;
        }

        /*
         * Every mixer cleanup stage is attempted independently. Some providers
         * throw from stop or flush after a concurrent EOF close; that must not
         * prevent the final close call from releasing the native line.
         */
        Throwable closeFailure = runCleanup(null, line::stop);
        closeFailure = runCleanup(closeFailure, line::flush);
        closeFailure = runCleanup(closeFailure, line::close);

        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            /*
             * Source cancellation runs on a daemon because a native decoder
             * can hold its read lock while the worker is inside read(). Start
             * it immediately, then perform one bounded wait instead of making
             * the UI wait through two consecutive timeout periods.
             */
            SourceCancellation cancellation = cancelSourceAsynchronously(cancellableSource);
            long shutdownDeadlineNanos = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(CLOSE_JOIN_MILLIS);
            boolean workerStopped = joinUntil(thread, shutdownDeadlineNanos);
            if (!workerStopped && !Thread.currentThread().isInterrupted()) {
                closeFailure = mergeFailure(
                        closeFailure,
                        new IllegalStateException(
                                "Playback worker did not stop within "
                                        + CLOSE_JOIN_MILLIS
                                        + " ms."
                        )
                );
            }

            if (cancellation != null) {
                boolean cancellationStopped = joinUntil(
                        cancellation.thread(),
                        shutdownDeadlineNanos
                );
                if (!cancellationStopped && !Thread.currentThread().isInterrupted()) {
                    closeFailure = mergeFailure(
                            closeFailure,
                            new IllegalStateException(
                                    "Playback source cancellation did not stop within "
                                            + CLOSE_JOIN_MILLIS
                                            + " ms."
                            )
                    );
                }

                closeFailure = mergeFailure(closeFailure, cancellation.failure());
            }
        }

        synchronized (monitor) {
            closeFailure = mergeFailure(closeFailure, shutdownCleanupFailure);
            if (closeFailure != null) {
                failure = mergeFailure(failure, closeFailure);
            }

            state = State.CLOSED;
        }

        rethrowCloseFailure(closeFailure);
    }

    private static boolean joinUntil(Thread thread, long deadlineNanos) {
        while (thread.isAlive()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                return false;
            }

            try {
                TimeUnit.NANOSECONDS.timedJoin(thread, remainingNanos);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return !thread.isAlive();
            }
        }

        return true;
    }

    static SourceCancellation cancelSourceAsynchronously(AudioInputStream source) {
        if (source == null) {
            return null;
        }

        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread cancellation = new Thread(() -> {
            try {
                source.close();
            } catch (IOException | RuntimeException | IOError | LinkageError error) {
                /*
                 * FlacPcmInputStream marks itself closed before releasing its
                 * native session. A later worker close can therefore be a
                 * no-op, so this first failure must be retained explicitly.
                 */
                closeFailure.set(error);
            }
        }, "jflac-playback-source-cancel");
        cancellation.setDaemon(true);
        cancellation.start();
        return new SourceCancellation(cancellation, closeFailure);
    }

    private static TrackDetails inspectTrack(Path input) throws Exception {
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(input.toFile())) {
            AudioFormat sourceFormat = stream.getFormat();
            AudioFormat playbackFormat = PlaybackAudioOutput.selectPlaybackFormat(
                    sourceFormat,
                    PlaybackAudioOutput.SystemPlaybackSupport.INSTANCE
            );
            long totalFrames = totalSourceFrames(sourceFormat, stream.getFrameLength());
            return new TrackDetails(sourceFormat, playbackFormat, totalFrames);
        }
    }

    static long totalSourceFrames(AudioFormat format, long streamFrameLength) {
        Object property = format.properties().get(JflacAudioFileProperties.TOTAL_SAMPLES);
        if (property instanceof Number number && number.longValue() > 0L) {
            return number.longValue();
        }

        return streamFrameLength == AudioSystem.NOT_SPECIFIED ? -1L : Math.max(0L, streamFrameLength);
    }

    /**
     * Skips decoded PCM frames, falling back to read-and-discard when an
     * InputStream reports zero progress from skip(). The returned frame count
     * can be smaller than requested when the target is beyond EOF.
     */
    static long skipPcmFrames(InputStream input, int frameSize, long targetFrames) throws IOException {
        return skipPcmFrames(input, frameSize, targetFrames, () -> false);
    }

    static long skipPcmFrames(
            InputStream input,
            int frameSize,
            long targetFrames,
            BooleanSupplier cancelled
    ) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(cancelled, "cancelled");
        if (frameSize <= 0 || targetFrames < 0L) {
            throw new IllegalArgumentException("Frame size must be positive and target frames non-negative.");
        }

        final long targetBytes;
        try {
            targetBytes = Math.multiplyExact(targetFrames, (long) frameSize);
        } catch (ArithmeticException error) {
            throw new IOException("Seek target is too large for a decoded PCM byte offset.", error);
        }

        long remaining = targetBytes;
        int discardBytes = alignedBufferSize(DISCARD_BUFFER_BYTES, frameSize);
        byte[] discard = new byte[discardBytes];
        while (remaining > 0L) {
            if (cancelled.getAsBoolean()) {
                throw new SeekSupersededException();
            }

            /*
             * Bound each skip request so a newer seek or close request is
             * observed promptly even when the underlying InputStream performs
             * skip by repeatedly decoding and discarding PCM.
             */
            long skipRequest = Math.min(remaining, discard.length);
            long skipped = input.skip(skipRequest);
            if (skipped > 0L) {
                remaining -= skipped;
                continue;
            }

            int requested = (int) Math.min(remaining, discard.length);
            requested -= requested % frameSize;
            if (requested == 0) {
                requested = frameSize;
            }

            int read = input.read(discard, 0, requested);
            if (read == -1) {
                break;
            }

            if (read == 0) {
                throw new IOException("Decoded PCM stream made no progress while seeking.");
            }

            remaining -= read;
        }

        long consumed = targetBytes - remaining;
        if (consumed % frameSize != 0L) {
            throw new IOException("Decoded PCM seek stopped inside a sample frame.");
        }

        return consumed / frameSize;
    }

    static long framesToMicros(long frames, float sampleRate) {
        return scaleNonNegative(frames, 1_000_000L, integralRate(sampleRate));
    }

    static long microsToFrames(long micros, float sampleRate) {
        return scaleNonNegative(micros, integralRate(sampleRate), 1_000_000L);
    }

    static long convertFrameCount(long frames, float sourceRate, float targetRate) {
        return scaleNonNegative(frames, integralRate(targetRate), integralRate(sourceRate));
    }

    private static long scaleNonNegative(long value, long numerator, long denominator) {
        if (value < 0L || numerator <= 0L || denominator <= 0L) {
            throw new IllegalArgumentException("Frame/time scaling values must be non-negative with positive rates.");
        }

        long quotient = value / denominator;
        long remainder = value % denominator;
        try {
            return Math.addExact(
                    Math.multiplyExact(quotient, numerator),
                    Math.multiplyExact(remainder, numerator) / denominator
            );
        } catch (ArithmeticException error) {
            return Long.MAX_VALUE;
        }
    }

    private static long integralRate(float rate) {
        if (!Float.isFinite(rate) || rate <= 0f) {
            throw new IllegalArgumentException("Audio sample rate must be finite and positive.");
        }

        return Math.round(rate);
    }

    private static int alignedBufferSize(int preferredBytes, int frameSize) {
        if (frameSize <= 0) {
            throw new IllegalArgumentException("PCM frame size must be positive.");
        }

        int aligned = preferredBytes - preferredBytes % frameSize;
        return aligned == 0 ? frameSize : aligned;
    }

    private static void writeFully(SourceDataLine line, byte[] buffer, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            int written = line.write(buffer, offset, length - offset);
            if (written <= 0) {
                throw new IOException("Audio output line stopped accepting PCM bytes.");
            }

            offset += written;
        }
    }

    private static Throwable closePlaybackInput(PlaybackInput input, Throwable failure) {
        if (input == null) {
            return failure;
        }

        try {
            input.close();
        } catch (IOException | RuntimeException | IOError | LinkageError closeFailure) {
            return mergeFailure(failure, closeFailure);
        }

        return failure;
    }

    private static Throwable runCleanup(Throwable failure, CleanupOperation operation) {
        try {
            operation.run();
        } catch (RuntimeException | IOError | LinkageError cleanupFailure) {
            return mergeFailure(failure, cleanupFailure);
        }

        return failure;
    }

    private static Throwable mergeFailure(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }

        if (first != next) {
            first.addSuppressed(next);
        }

        return first;
    }

    private static void rethrowCloseFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }

        if (failure instanceof Error errorFailure) {
            throw errorFailure;
        }

        if (failure != null) {
            throw new IllegalStateException("Playback resources could not be closed.", failure);
        }
    }

    @FunctionalInterface
    private interface CleanupOperation {
        void run();
    }

    private record PositionState(
            State state,
            long segmentStartSourceFrame,
            long segmentWrittenOutputFrames,
            long lastKnownPositionMicros
    ) {
    }

    private record SeekRequest(long sourceFrame, long revision) {
    }

    record SourceCancellation(
            Thread thread,
            AtomicReference<Throwable> closeFailure
    ) {
        Throwable failure() {
            return closeFailure.get();
        }
    }

    private static final class SeekSupersededException extends IOException {
        private SeekSupersededException() {
            super("Playback seek was superseded by a newer request.");
        }
    }

    private record TrackDetails(
            AudioFormat sourceFormat,
            AudioFormat playbackFormat,
            long totalSourceFrames
    ) {
    }

    private static final class PlaybackInput implements AutoCloseable {
        private final AudioInputStream sourceStream;
        private final AudioInputStream playbackStream;
        private final long actualSourceFrame;

        private PlaybackInput(
                AudioInputStream sourceStream,
                AudioInputStream playbackStream,
                long actualSourceFrame
        ) {
            this.sourceStream = sourceStream;
            this.playbackStream = playbackStream;
            this.actualSourceFrame = actualSourceFrame;
        }

        private AudioInputStream playbackStream() {
            return playbackStream;
        }

        private long actualSourceFrame() {
            return actualSourceFrame;
        }

        @Override
        public void close() throws IOException {
            Throwable closeFailure = null;
            if (playbackStream != sourceStream) {
                try {
                    playbackStream.close();
                } catch (IOException | RuntimeException | IOError | LinkageError error) {
                    closeFailure = error;
                }
            }

            try {
                sourceStream.close();
            } catch (IOException | RuntimeException | IOError | LinkageError error) {
                closeFailure = mergeFailure(closeFailure, error);
            }

            if (closeFailure instanceof IOException ioFailure) {
                throw ioFailure;
            }

            if (closeFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }

            if (closeFailure instanceof Error errorFailure) {
                throw errorFailure;
            }
        }
    }
}
