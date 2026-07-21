package org.zzvsjs.jflac.examples.playback;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class ConsolePlaybackRunnerTest {
    @Test
    public void selectionEndAndQuitDoNotOpenAnAudioDevice(@TempDir Path workingDirectory) {
        PlaybackSessionFactory unexpectedAudio = ignored -> {
            throw new AssertionError("File selection must finish before audio is opened.");
        };

        try (PrintStream output = sink(); PrintStream error = sink()) {
            assertEquals(
                    JavaSoundPlaybackDemo.EXIT_INVALID_INPUT,
                    ConsolePlaybackRunner.run(
                            PlaybackArguments.parse(new String[] { "--console" }),
                            new ByteArrayInputStream(new byte[0]),
                            output,
                            error,
                            workingDirectory,
                            unexpectedAudio
                    )
            );
            assertEquals(
                    JavaSoundPlaybackDemo.EXIT_SUCCESS,
                    ConsolePlaybackRunner.run(
                            PlaybackArguments.parse(new String[] { "--console" }),
                            bytes("q\n"),
                            output,
                            error,
                            workingDirectory,
                            unexpectedAudio
                    )
            );
        }
    }

    @Test
    public void headlessPlaybackNeverReadsStandardInput(@TempDir Path workingDirectory)
            throws Exception {
        Path input = Files.createFile(workingDirectory.resolve("track.flac"));
        FakePlaybackSession session = new FakePlaybackSession(input, true);
        InputStream unreadableInput = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("--no-controls must not read standard input.");
            }
        };

        try (PrintStream output = sink(); PrintStream error = sink()) {
            assertEquals(
                    JavaSoundPlaybackDemo.EXIT_SUCCESS,
                    ConsolePlaybackRunner.run(
                            PlaybackArguments.parse(
                                    new String[] { "--no-controls", input.toString() }
                            ),
                            unreadableInput,
                            output,
                            error,
                            workingDirectory,
                            ignored -> session
                    )
            );
        }

        assertTrue(session.started);
        assertTrue(session.closed);
    }

    @Test
    public void consoleCommandsReachTheActiveSession(@TempDir Path workingDirectory)
            throws Exception {
        Path input = Files.createFile(workingDirectory.resolve("track.flac"));
        FakePlaybackSession session = new FakePlaybackSession(input, false);

        try (PrintStream output = sink(); PrintStream error = sink()) {
            assertEquals(
                    JavaSoundPlaybackDemo.EXIT_SUCCESS,
                    ConsolePlaybackRunner.run(
                            PlaybackArguments.parse(
                                    new String[] { "--console", input.toString() }
                            ),
                            bytes("p\n+10\nq\n"),
                            output,
                            error,
                            workingDirectory,
                            ignored -> session
                    )
            );
        }

        assertEquals(1, session.toggleCount);
        assertEquals(List.of(10_000_000L), session.relativeSeeks);
        assertTrue(session.closed);
    }

    @Test
    public void providerErrorsUseTheStructuredConsoleFailurePath(
            @TempDir Path workingDirectory
    ) throws Exception {
        Path input = Files.createFile(workingDirectory.resolve("track.flac"));
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();

        try (PrintStream output = sink();
             PrintStream error = new PrintStream(
                     errorBytes,
                     true,
                     StandardCharsets.UTF_8
             )) {
            assertEquals(
                    JavaSoundPlaybackDemo.EXIT_FAILURE,
                    ConsolePlaybackRunner.run(
                            PlaybackArguments.parse(
                                    new String[] { "--no-controls", input.toString() }
                            ),
                            new ByteArrayInputStream(new byte[0]),
                            output,
                            error,
                            workingDirectory,
                            ignored -> {
                                throw new IOError(
                                        new IOException("test provider failure")
                                );
                            }
                    )
            );
        }

        assertTrue(
                errorBytes.toString(StandardCharsets.UTF_8)
                        .contains("Playback failed: test provider failure")
        );
    }

    @Test
    public void openCommandResolvesRelativePathsFromTheSuppliedDirectory(
            @TempDir Path workingDirectory
    ) throws Exception {
        Path first = Files.createFile(workingDirectory.resolve("first.flac"));
        Path second = Files.createFile(workingDirectory.resolve("second.flac"));
        List<Path> openedInputs = new ArrayList<>();

        try (PrintStream output = sink(); PrintStream error = sink()) {
            assertEquals(
                    JavaSoundPlaybackDemo.EXIT_SUCCESS,
                    ConsolePlaybackRunner.run(
                            PlaybackArguments.parse(
                                    new String[] { "--console", first.toString() }
                            ),
                            bytes("open second.flac\nq\n"),
                            output,
                            error,
                            workingDirectory,
                            input -> {
                                openedInputs.add(input);
                                return new FakePlaybackSession(input, false);
                            }
                    )
            );
        }

        assertEquals(List.of(first, second), openedInputs);
    }

    @Test
    public void interruptionReturnsFailureAndRestoresTheInterruptFlag(
            @TempDir Path workingDirectory
    ) throws Exception {
        Path input = Files.createFile(workingDirectory.resolve("track.flac"));
        FakePlaybackSession session = new FakePlaybackSession(input, false);
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();

        /*
         * The input pump waits until the runner interrupts it during cleanup.
         * This keeps its event queue empty, so the interrupted control poll is
         * the deterministic path exercised by the test.
         */
        InputStream waitingInput = new InputStream() {
            private final CountDownLatch waitForever = new CountDownLatch(1);

            @Override
            public int read() {
                try {
                    waitForever.await();
                    return -1;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
        };

        try (PrintStream output = sink();
             PrintStream error = new PrintStream(
                     errorBytes,
                     true,
                     StandardCharsets.UTF_8
             )) {
            Thread.currentThread().interrupt();
            assertEquals(
                    JavaSoundPlaybackDemo.EXIT_FAILURE,
                    ConsolePlaybackRunner.run(
                            PlaybackArguments.parse(
                                    new String[] { "--console", input.toString() }
                            ),
                            waitingInput,
                            output,
                            error,
                            workingDirectory,
                            ignored -> session
                    )
            );
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // Do not leak the deliberate interrupt into later JUnit tests.
            Thread.interrupted();
        }

        assertTrue(errorBytes.toString(StandardCharsets.UTF_8).contains("interrupted"));
        assertTrue(session.closed);
    }

    @Test
    public void quittingReleasesAnInputPumpWaitingForCommandAcknowledgement(
            @TempDir Path workingDirectory
    ) throws Exception {
        Path input = Files.createFile(workingDirectory.resolve("track.flac"));
        Set<Long> existingPumpIds = consoleInputThreads().stream()
                .map(Thread::threadId)
                .collect(Collectors.toSet());
        String commands = "q\n" + "status\n".repeat(200);

        try (PrintStream output = sink(); PrintStream error = sink()) {
            assertEquals(
                    JavaSoundPlaybackDemo.EXIT_SUCCESS,
                    ConsolePlaybackRunner.run(
                            PlaybackArguments.parse(
                                    new String[] { "--console", input.toString() }
                            ),
                            bytes(commands),
                            output,
                            error,
                            workingDirectory,
                            ignored -> new FakePlaybackSession(input, false)
                    )
            );
        }

        for (Thread pump : consoleInputThreads()) {
            if (!existingPumpIds.contains(pump.threadId())) {
                pump.join(1_000L);
                assertFalse(pump.isAlive(), "The console input pump should stop after Quit.");
            }
        }
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

        assertEquals(
                "[playing] 00:30 / 02:00   25%  music.flac",
                ConsolePlaybackRunner.renderProgress(known)
        );
        assertEquals(
                "[paused] 00:05 / --:--  stream.oga",
                ConsolePlaybackRunner.renderProgress(unknown)
        );
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static PrintStream sink() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    private static List<Thread> consoleInputThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> "jflac-console-input".equals(thread.getName()))
                .toList();
    }

    private static AudioFormat pcm(float sampleRate, int bitsPerSample, int channels) {
        int bytesPerSample = (bitsPerSample + 7) / 8;
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                bitsPerSample,
                channels,
                bytesPerSample * channels,
                sampleRate,
                false
        );
    }

    private static final class FakePlaybackSession implements PlaybackSession {
        private final Path input;
        private final boolean endImmediately;
        private final List<Long> relativeSeeks = new ArrayList<>();

        private State state = State.NEW;
        private boolean started;
        private boolean closed;
        private int toggleCount;

        private FakePlaybackSession(Path input, boolean endImmediately) {
            this.input = input;
            this.endImmediately = endImmediately;
        }

        @Override
        public Path input() {
            return input;
        }

        @Override
        public AudioFormat sourceFormat() {
            return pcm(44_100f, 16, 2);
        }

        @Override
        public AudioFormat playbackFormat() {
            return sourceFormat();
        }

        @Override
        public void start(long startMicros, boolean paused) {
            started = true;
            state = endImmediately ? State.ENDED : paused ? State.PAUSED : State.PLAYING;
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
            // This fake only records the relative seek command used by the test.
        }

        @Override
        public void seekByMicros(long deltaMicros) {
            relativeSeeks.add(deltaMicros);
        }

        @Override
        public Snapshot snapshot() {
            return new Snapshot(input, state, 0L, 120_000_000L, null);
        }

        @Override
        public void close() {
            closed = true;
            state = State.CLOSED;
        }
    }
}
