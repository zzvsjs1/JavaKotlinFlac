package org.zzvsjs.jflac.examples;

import org.zzvsjs.jflac.sound.JflacAudioFileProperties;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
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
    private volatile AudioInputStream cancellableSource;

    JavaSoundPlaybackSession(Path input) throws Exception {
        this.input = JavaSoundPlaybackDemo.validateInputPath(input);

        TrackDetails details = inspectTrack(this.input);
        this.sourceFormat = details.sourceFormat();
        this.playbackFormat = details.playbackFormat();
        this.totalSourceFrames = details.totalSourceFrames();
        this.durationMicros = totalSourceFrames < 0L
                ? -1L
                : framesToMicros(totalSourceFrames, sourceFormat.getSampleRate());
        this.line = JavaSoundPlaybackDemo.openSourceLine(playbackFormat);
    }

    public Path input() {
        return input;
    }

    public AudioFormat sourceFormat() {
        return sourceFormat;
    }

    public AudioFormat playbackFormat() {
        return playbackFormat;
    }

    public long totalSourceFrames() {
        return totalSourceFrames;
    }

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

    public void togglePause() {
        synchronized (monitor) {
            ensureControllable();
            pauseRequested = !pauseRequested;
            monitor.notifyAll();
        }
    }

    public void pause() {
        synchronized (monitor) {
            ensureControllable();
            pauseRequested = true;
            monitor.notifyAll();
        }
    }

    public void resume() {
        synchronized (monitor) {
            ensureControllable();
            pauseRequested = false;
            monitor.notifyAll();
        }
    }

    public boolean isPauseRequested() {
        synchronized (monitor) {
            return pauseRequested;
        }
    }

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

    public Snapshot snapshot() {
        synchronized (monitor) {
            long position = calculatePositionMicros();
            return new Snapshot(input, state, position, durationMicros, failure);
        }
    }

    private void ensureControllable() {
        if (state == State.NEW) {
            throw new IllegalStateException("Playback session has not been started.");
        }
        if (state == State.ENDED || state == State.FAILED || state == State.CLOSED || closeRequested) {
            throw new IllegalStateException("Playback session is no longer active.");
        }
    }

    private void runPlayback() {
        PlaybackInput openedInput = null;
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
                            state = State.ENDED;
                            lastKnownPositionMicros = durationMicros >= 0L
                                    ? durationMicros
                                    : calculateWrittenPositionMicros();
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
        } catch (Throwable error) {
            synchronized (monitor) {
                if (!closeRequested) {
                    failure = error;
                    state = State.FAILED;
                }
            }
        } finally {
            closePlaybackInputQuietly(openedInput);
            cancellableSource = null;
            try {
                line.stop();
            } catch (RuntimeException ignored) {
                // The close path may already have closed the line to unblock I/O.
            }
            line.close();
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
    private PlaybackInput openLatestRequestedInput() throws Throwable {
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

    private PlaybackInput openPlaybackInput(long targetSourceFrame, long revision) throws Throwable {
        AudioInputStream source = AudioSystem.getAudioInputStream(input.toFile());
        try {
            synchronized (monitor) {
                if (closeRequested) {
                    source.close();
                    throw new IOException("Playback was closed while reopening the music file.");
                }
                cancellableSource = source;
            }

            if (!JavaSoundPlaybackDemo.sameFormat(source.getFormat(), sourceFormat)) {
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
            if (!JavaSoundPlaybackDemo.sameFormat(sourceFormat, playbackFormat)) {
                playback = AudioSystem.getAudioInputStream(playbackFormat, source);
            }
            return new PlaybackInput(source, playback, actualSourceFrame);
        } catch (Throwable error) {
            if (cancellableSource == source) {
                cancellableSource = null;
            }
            try {
                source.close();
            } catch (Throwable closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    private boolean isSeekSuperseded(long revision) {
        synchronized (monitor) {
            return closeRequested || seekRevision != revision;
        }
    }

    private long calculatePositionMicros() {
        if (state == State.ENDED && durationMicros >= 0L) {
            return durationMicros;
        }
        if (state == State.CLOSED || !line.isOpen()) {
            return clampDuration(lastKnownPositionMicros);
        }

        long queuedOutputFrames = 0L;
        try {
            int queuedBytes = Math.max(0, line.getBufferSize() - line.available());
            queuedOutputFrames = queuedBytes / playbackFormat.getFrameSize();
        } catch (RuntimeException ignored) {
            // A concurrent close falls back to the last completed write.
        }

        long audibleOutputFrames = Math.max(0L, segmentWrittenOutputFrames - queuedOutputFrames);
        long audibleSourceFrames = convertFrameCount(
                audibleOutputFrames,
                playbackFormat.getSampleRate(),
                sourceFormat.getSampleRate()
        );
        long sourceFrame;
        try {
            sourceFrame = Math.addExact(segmentStartSourceFrame, audibleSourceFrames);
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
         * Closing the output line releases a worker blocked in drain/write.
         * The worker normally remains the sole owner of AudioInputStream
         * closure, because closing the pull decoder from this thread could wait
         * on the same native read lock and make this method unbounded.
         */
        try {
            line.stop();
            line.flush();
            line.close();
        } catch (RuntimeException ignored) {
            // The worker may already have closed the line at EOF or failure.
        }

        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            joinWorker(thread, CLOSE_JOIN_MILLIS);
            if (thread.isAlive()) {
                /*
                 * A pathological provider may ignore interruption while
                 * reading. Run the final source cancellation on a daemon so a
                 * provider lock cannot block the console/control thread.
                 */
                cancelSourceAsynchronously(cancellableSource);
                joinWorker(thread, CLOSE_JOIN_MILLIS);
            }
        }

        synchronized (monitor) {
            state = State.CLOSED;
        }
    }

    private static void joinWorker(Thread thread, long timeoutMillis) {
        try {
            thread.join(timeoutMillis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static void cancelSourceAsynchronously(AudioInputStream source) {
        if (source == null) {
            return;
        }
        Thread cancellation = new Thread(() -> {
            try {
                source.close();
            } catch (IOException ignored) {
                // The playback worker reports the primary read/close failure.
            }
        }, "jflac-console-playback-cancel");
        cancellation.setDaemon(true);
        cancellation.start();
    }

    private static TrackDetails inspectTrack(Path input) throws Exception {
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(input.toFile())) {
            AudioFormat sourceFormat = stream.getFormat();
            AudioFormat playbackFormat = JavaSoundPlaybackDemo.selectPlaybackFormat(
                    sourceFormat,
                    JavaSoundPlaybackDemo.SystemPlaybackSupport.INSTANCE
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

    private static void closePlaybackInputQuietly(PlaybackInput input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // A prior playback failure remains the primary diagnostic.
        }
    }

    private record SeekRequest(long sourceFrame, long revision) {
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
            IOException failure = null;
            if (playbackStream != sourceStream) {
                try {
                    playbackStream.close();
                } catch (IOException error) {
                    failure = error;
                }
            }

            try {
                sourceStream.close();
            } catch (IOException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }

            if (failure != null) {
                throw failure;
            }
        }
    }
}
