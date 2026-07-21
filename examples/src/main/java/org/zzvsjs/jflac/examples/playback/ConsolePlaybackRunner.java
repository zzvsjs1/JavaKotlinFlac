package org.zzvsjs.jflac.examples.playback;

import java.io.BufferedReader;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runs the line-command frontend without depending on a native terminal. */
final class ConsolePlaybackRunner {
    private static final int EVENT_QUEUE_CAPACITY = 64;
    private static final long CONTROL_POLL_MILLIS = 200L;
    private static final long PROGRESS_REFRESH_NANOS = TimeUnit.SECONDS.toNanos(1L);

    private ConsolePlaybackRunner() {
    }

    static int run(
            PlaybackArguments options,
            InputStream standardInput,
            PrintStream output,
            PrintStream errorOutput,
            Path workingDirectory,
            PlaybackSessionFactory sessionFactory
    ) {
        BufferedReader console = new BufferedReader(
                new InputStreamReader(standardInput, StandardCharsets.UTF_8)
        );

        Path initialInput = options.input();
        if (initialInput == null) {
            if (!options.controlsEnabled()) {
                errorOutput.println("Input error: --no-controls requires an input file argument.");
                return JavaSoundPlaybackDemo.EXIT_INVALID_INPUT;
            }

            final InitialSelection selection;
            try {
                selection = promptForMusicFile(console, output, workingDirectory);
            } catch (IOException | SecurityException error) {
                errorOutput.println(
                        "File selection failed: " + PlaybackMessages.rootCauseMessage(error)
                );
                return JavaSoundPlaybackDemo.EXIT_FAILURE;
            }

            if (selection instanceof QuitSelection) {
                return JavaSoundPlaybackDemo.EXIT_SUCCESS;
            }

            if (selection instanceof InputEndedSelection) {
                errorOutput.println("No music file was selected before console input ended.");
                return JavaSoundPlaybackDemo.EXIT_INVALID_INPUT;
            }

            initialInput = ((SelectedInput) selection).input();
        } else {
            try {
                initialInput = PlaybackFiles.validateInputPath(initialInput);
            } catch (IllegalArgumentException | SecurityException error) {
                errorOutput.println("Input error: " + error.getMessage());
                return JavaSoundPlaybackDemo.EXIT_INVALID_INPUT;
            }
        }

        try {
            return runPlaybackLoop(
                    initialInput,
                    options,
                    console,
                    output,
                    workingDirectory,
                    sessionFactory
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            errorOutput.println("Playback was interrupted.");
            return JavaSoundPlaybackDemo.EXIT_FAILURE;
        } catch (Exception | IOError | LinkageError error) {
            errorOutput.println("Playback failed: " + PlaybackMessages.rootCauseMessage(error));
            return JavaSoundPlaybackDemo.EXIT_FAILURE;
        }
    }

    private static int runPlaybackLoop(
            Path firstInput,
            PlaybackArguments options,
            BufferedReader console,
            PrintStream output,
            Path workingDirectory,
            PlaybackSessionFactory sessionFactory
    ) throws Exception {
        /*
         * A pipe can supply commands much faster than audio can consume them.
         * The bounded queue applies backpressure to the daemon input thread and
         * prevents an unbounded backlog from exhausting memory.
         */
        BlockingQueue<ConsoleEvent> events = new ArrayBlockingQueue<>(EVENT_QUEUE_CAPACITY);
        boolean consoleInputAvailable = options.controlsEnabled();
        Thread inputPump = consoleInputAvailable
                ? startConsoleInputPump(console, events)
                : null;

        Path currentInput = firstInput;
        long startMicros = options.startMicros();
        boolean startsPaused = options.startPaused();
        try {
            /*
             * An OPEN outcome is handled outside the session's resource scope,
             * so the current mixer line is closed before the next is acquired.
             */
            while (true) {
                TrackOutcome outcome = playOneTrack(
                        currentInput,
                        startMicros,
                        startsPaused,
                        consoleInputAvailable,
                        events,
                        output,
                        workingDirectory,
                        sessionFactory
                );
                if (outcome instanceof ExitPlayback) {
                    return JavaSoundPlaybackDemo.EXIT_SUCCESS;
                }

                OpenNextTrack openNext = (OpenNextTrack) outcome;
                currentInput = openNext.input();
                consoleInputAvailable = openNext.consoleInputAvailable();
                startMicros = 0L;
                startsPaused = false;
            }
        } finally {
            if (inputPump != null) {
                /*
                 * Release a producer waiting for queue space or command
                 * acknowledgement. The caller owns stdin, so it is deliberately
                 * not closed here. A native stdin read may ignore interruption
                 * and remain as a process-lifetime daemon until input arrives;
                 * the pump checks its interrupt before it can publish or read
                 * another command.
                 */
                inputPump.interrupt();
            }
        }
    }

    private static TrackOutcome playOneTrack(
            Path input,
            long startMicros,
            boolean startsPaused,
            boolean initialConsoleInputAvailable,
            BlockingQueue<ConsoleEvent> events,
            PrintStream output,
            Path workingDirectory,
            PlaybackSessionFactory sessionFactory
    ) throws Exception {
        int progressWidth = 0;
        PlaybackSession opened = Objects.requireNonNull(
                sessionFactory.open(input),
                "sessionFactory returned null"
        );
        try (PlaybackSession playback = opened) {
            printTrackDetails(output, input, playback, initialConsoleInputAvailable);
            playback.start(startMicros, startsPaused);

            boolean consoleInputAvailable = initialConsoleInputAvailable;
            TrackCommandHandler commandHandler = new TrackCommandHandler(
                    playback,
                    output,
                    workingDirectory
            );
            long nextProgressRefresh = 0L;

            while (true) {
                PlaybackSession.Snapshot snapshot = playback.snapshot();
                if (snapshot.state() == PlaybackSession.State.FAILED) {
                    throw new IOException(
                            "Audio worker failed: "
                                    + PlaybackMessages.rootCauseMessage(snapshot.failure()),
                            snapshot.failure()
                    );
                }

                if (snapshot.state() == PlaybackSession.State.ENDED) {
                    progressWidth = printProgress(output, snapshot, progressWidth);
                    clearProgressLine(output, progressWidth);
                    progressWidth = 0;
                    output.println("Playback finished: " + input.getFileName());
                    return ExitPlayback.INSTANCE;
                }

                ConsoleEvent event = consoleInputAvailable
                        ? events.poll(CONTROL_POLL_MILLIS, TimeUnit.MILLISECONDS)
                        : null;
                if (!consoleInputAvailable) {
                    Thread.sleep(CONTROL_POLL_MILLIS);
                }

                if (event instanceof ConsoleInputEnded inputEnded) {
                    clearProgressLine(output, progressWidth);
                    progressWidth = 0;
                    consoleInputAvailable = false;
                    reportConsoleInputEnded(output, playback, inputEnded);
                    continue;
                }

                if (event instanceof ConsoleLine consoleLine) {
                    clearProgressLine(output, progressWidth);
                    progressWidth = 0;
                    TrackAction action = commandHandler.handleLine(consoleLine.line());
                    if (action instanceof ExitTrack) {
                        /*
                         * Keep the pump at its interruptible acknowledgement
                         * wait until the outer finally block stops it. Without
                         * this handshake it could re-enter native readLine()
                         * after publishing Quit and outlive this invocation.
                        */
                        return ExitPlayback.INSTANCE;
                    }

                    consoleLine.allowNextRead();

                    if (action instanceof OpenInput openInput) {
                        return new OpenNextTrack(
                                openInput.input(),
                                consoleInputAvailable
                        );
                    }
                }

                long now = System.nanoTime();
                if (now >= nextProgressRefresh) {
                    progressWidth = printProgress(
                            output,
                            playback.snapshot(),
                            progressWidth
                    );
                    nextProgressRefresh = now + PROGRESS_REFRESH_NANOS;
                }
            }
        } finally {
            clearProgressLine(output, progressWidth);
        }
    }

    private static void printTrackDetails(
            PrintStream output,
            Path input,
            PlaybackSession playback,
            boolean consoleInputAvailable
    ) {
        output.println("Opening through Java Sound: " + input);
        output.println(
                "Decoded format:  "
                        + PlaybackAudioOutput.describeAudioFormat(playback.sourceFormat())
        );
        if (!PlaybackAudioOutput.isSamePcmFormat(
                playback.sourceFormat(),
                playback.playbackFormat()
        )) {
            output.println(
                    "Playback format: "
                            + PlaybackAudioOutput.describeAudioFormat(
                            playback.playbackFormat()
                    )
            );
        }

        if (consoleInputAvailable) {
            output.println(
                    "Enter 'help' for controls. Commands remain available during playback."
            );
        }
    }

    private static void reportConsoleInputEnded(
            PrintStream output,
            PlaybackSession playback,
            ConsoleInputEnded inputEnded
    ) {
        if (inputEnded.failure() != null) {
            output.println(
                    "Console input stopped: "
                            + PlaybackMessages.rootCauseMessage(inputEnded.failure())
            );
        } else {
            output.println("Console input ended; playback will continue to the end.");
        }

        if (playback.isPauseRequested()) {
            playback.resume();
            output.println("Playback resumed because no further console command can arrive.");
        }
    }

    private static Thread startConsoleInputPump(
            BufferedReader console,
            BlockingQueue<ConsoleEvent> events
    ) {
        Thread inputThread = new Thread(() -> {
            try {
                String line;
                while ((line = console.readLine()) != null) {
                    ConsoleLine event = new ConsoleLine(line);
                    events.put(event);
                    event.awaitAcknowledgement();
                }

                events.put(new ConsoleInputEnded(null));
            } catch (IOException | RuntimeException error) {
                putTerminalEvent(events, new ConsoleInputEnded(error));
            } catch (InterruptedException interrupted) {
                // The runner interrupts its owned pump when playback exits.
                Thread.currentThread().interrupt();
            }
        }, "jflac-console-input");
        inputThread.setDaemon(true);
        inputThread.start();
        return inputThread;
    }

    private static void putTerminalEvent(
            BlockingQueue<ConsoleEvent> events,
            ConsoleInputEnded event
    ) {
        try {
            events.put(event);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static InitialSelection promptForMusicFile(
            BufferedReader console,
            PrintStream output,
            Path workingDirectory
    ) throws IOException {
        List<Path> candidates = PlaybackFiles.listMusicFiles(workingDirectory);
        while (true) {
            printMusicMenu(output, candidates);
            output.print("Enter a number or file path (q to quit): ");
            output.flush();

            String choice = console.readLine();
            if (choice == null) {
                return new InputEndedSelection();
            }

            if ("q".equalsIgnoreCase(choice.trim())) {
                return new QuitSelection();
            }

            try {
                Path selected = PlaybackFiles.resolveMusicChoice(
                        choice,
                        candidates,
                        workingDirectory
                );
                return new SelectedInput(PlaybackFiles.validateInputPath(selected));
            } catch (IllegalArgumentException | SecurityException error) {
                output.println("Selection error: " + error.getMessage());
            }
        }
    }

    private static boolean isQuitCommand(String selection) {
        return "q".equalsIgnoreCase(selection)
                || "quit".equalsIgnoreCase(selection)
                || "exit".equalsIgnoreCase(selection);
    }

    private static void printMusicMenu(PrintStream output, List<Path> candidates) {
        if (candidates.isEmpty()) {
            output.println("No .flac/.oga/.ogg files were found in the working directory.");
            return;
        }

        output.println("Music files:");
        for (int index = 0; index < candidates.size(); index++) {
            output.printf("  %d. %s%n", index + 1, candidates.get(index).getFileName());
        }
    }

    static String renderProgress(PlaybackSession.Snapshot snapshot) {
        String state = snapshot.state().name().toLowerCase(Locale.ROOT);
        String position = PlaybackArguments.formatTime(snapshot.positionMicros());
        String duration = snapshot.durationMicros() < 0L
                ? "--:--"
                : PlaybackArguments.formatTime(snapshot.durationMicros());
        String percentage = "";
        if (snapshot.durationMicros() > 0L) {
            long boundedPosition = Math.min(
                    snapshot.positionMicros(),
                    snapshot.durationMicros()
            );
            long percent = Math.round((boundedPosition * 100.0) / snapshot.durationMicros());
            percentage = String.format(Locale.ROOT, "  %3d%%", percent);
        }

        return String.format(
                Locale.ROOT,
                "[%s] %s / %s%s  %s",
                state,
                position,
                duration,
                percentage,
                snapshot.input().getFileName()
        );
    }

    private static int printProgress(
            PrintStream output,
            PlaybackSession.Snapshot snapshot,
            int previousWidth
    ) {
        String progress = renderProgress(snapshot);
        output.print("\r" + progress);
        if (previousWidth > progress.length()) {
            output.print(" ".repeat(previousWidth - progress.length()));
        }

        output.flush();
        return progress.length();
    }

    private static void clearProgressLine(PrintStream output, int width) {
        if (width <= 0) {
            return;
        }

        output.print("\r" + " ".repeat(width) + "\r");
        output.flush();
    }

    private static final class TrackCommandHandler {
        private final PlaybackSession playback;
        private final PrintStream output;
        private final Path workingDirectory;

        private boolean awaitingFileChoice;
        private List<Path> menuCandidates = List.of();

        private TrackCommandHandler(
                PlaybackSession playback,
                PrintStream output,
                Path workingDirectory
        ) {
            this.playback = playback;
            this.output = output;
            this.workingDirectory = workingDirectory;
        }

        private TrackAction handleLine(String line) {
            if (awaitingFileChoice) {
                return handleFileChoice(line);
            }

            final ConsolePlaybackCommand command;
            try {
                command = ConsolePlaybackCommand.parse(line);
            } catch (IllegalArgumentException commandError) {
                output.println("Command error: " + commandError.getMessage());
                return ContinueTrack.INSTANCE;
            }

            return executeCommand(command);
        }

        private TrackAction handleFileChoice(String line) {
            String selection = line.trim();
            if (isQuitCommand(selection)) {
                output.println("Playback stopped.");
                return ExitTrack.INSTANCE;
            }

            if ("c".equalsIgnoreCase(selection)
                    || "cancel".equalsIgnoreCase(selection)) {
                awaitingFileChoice = false;
                output.println("File selection cancelled; current playback continues.");
                return ContinueTrack.INSTANCE;
            }

            if ("h".equalsIgnoreCase(selection)
                    || "help".equalsIgnoreCase(selection)) {
                output.println("Enter a listed number, a file path, 'cancel', or 'q'.");
                return ContinueTrack.INSTANCE;
            }

            try {
                Path nextInput = PlaybackFiles.validateInputPath(
                        PlaybackFiles.resolveMusicChoice(
                                line,
                                menuCandidates,
                                workingDirectory
                        )
                );
                return new OpenInput(nextInput);
            } catch (IllegalArgumentException | SecurityException selectionError) {
                output.println("Selection error: " + selectionError.getMessage());
                printMusicMenu(output, menuCandidates);
                return ContinueTrack.INSTANCE;
            }
        }

        private TrackAction executeCommand(ConsolePlaybackCommand command) {
            try {
                switch (command.type()) {
                    case TOGGLE_PAUSE -> playback.togglePause();
                    case PAUSE -> playback.pause();
                    case RESUME -> playback.resume();
                    case SEEK_ABSOLUTE -> {
                        playback.seekToMicros(command.timeMicros());
                        output.println(
                                "Seeking to "
                                        + PlaybackArguments.formatTime(command.timeMicros())
                                        + "..."
                        );
                    }

                    case SEEK_RELATIVE -> {
                        playback.seekByMicros(command.timeMicros());
                        String direction = command.timeMicros() < 0L
                                ? "backwards"
                                : "forwards";
                        output.println(
                                "Seeking " + direction + " by "
                                        + PlaybackArguments.formatTime(
                                        Math.abs(command.timeMicros())
                                )
                                        + "..."
                        );
                    }

                    case STATUS -> output.println(renderProgress(playback.snapshot()));
                    case LIST -> {
                        menuCandidates = PlaybackFiles.listMusicFiles(workingDirectory);
                        printMusicMenu(output, menuCandidates);
                    }

                    case OPEN -> {
                        if (command.argument() == null) {
                            menuCandidates = PlaybackFiles.listMusicFiles(workingDirectory);
                            printMusicMenu(output, menuCandidates);
                            output.println(
                                    "Enter a number or file path "
                                            + "('cancel' keeps this track):"
                            );
                            awaitingFileChoice = true;
                        } else {
                            Path requested = PlaybackFiles.resolveAgainstWorkingDirectory(
                                    Path.of(command.argument()),
                                    workingDirectory
                            );
                            return new OpenInput(
                                    PlaybackFiles.validateInputPath(requested)
                            );
                        }
                    }

                    case HELP -> output.println(ConsolePlaybackCommand.helpText());
                    case QUIT -> {
                        output.println("Playback stopped.");
                        return ExitTrack.INSTANCE;
                    }
                }
            } catch (IOException
                     | IllegalArgumentException
                     | IllegalStateException
                     | SecurityException commandError) {
                /*
                 * A failed LIST or OPEN command is local to that command. Keep
                 * the healthy session playing after transient file problems.
                 */
                output.println(
                        "Command error: " + PlaybackMessages.rootCauseMessage(commandError)
                );
            }

            return ContinueTrack.INSTANCE;
        }
    }

    private sealed interface TrackOutcome permits ExitPlayback, OpenNextTrack {
    }

    private enum ExitPlayback implements TrackOutcome {
        INSTANCE
    }

    private record OpenNextTrack(
            Path input,
            boolean consoleInputAvailable
    ) implements TrackOutcome {
    }

    private sealed interface TrackAction permits ContinueTrack, ExitTrack, OpenInput {
    }

    private enum ContinueTrack implements TrackAction {
        INSTANCE
    }

    private enum ExitTrack implements TrackAction {
        INSTANCE
    }

    private record OpenInput(Path input) implements TrackAction {
    }

    private sealed interface ConsoleEvent permits ConsoleLine, ConsoleInputEnded {
    }

    private static final class ConsoleLine implements ConsoleEvent {
        private final String line;
        private final CountDownLatch acknowledgement = new CountDownLatch(1);

        private ConsoleLine(String line) {
            this.line = line;
        }

        private String line() {
            return line;
        }

        private void allowNextRead() {
            acknowledgement.countDown();
        }

        private void awaitAcknowledgement() throws InterruptedException {
            acknowledgement.await();
        }
    }

    private record ConsoleInputEnded(Throwable failure) implements ConsoleEvent {
    }

    private sealed interface InitialSelection
            permits SelectedInput, QuitSelection, InputEndedSelection {
    }

    private record SelectedInput(Path input) implements InitialSelection {
    }

    private record QuitSelection() implements InitialSelection {
    }

    private record InputEndedSelection() implements InitialSelection {
    }
}
