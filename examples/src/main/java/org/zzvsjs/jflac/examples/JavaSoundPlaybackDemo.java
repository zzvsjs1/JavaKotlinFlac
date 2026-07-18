package org.zzvsjs.jflac.examples;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * FLAC/Ogg FLAC terminal player built on the jflac Java Sound SPI.
 *
 * <p>The preferred native-terminal Lanterna UI provides a file-system browser,
 * buttons, keyboard shortcuts, and mouse seeking. Automatic mode falls back to
 * line commands when the process has no real terminal; {@code --tui} requires
 * the full-screen frontend and {@code --no-controls} supports automation.</p>
 */
public final class JavaSoundPlaybackDemo {
    private static final float[] FALLBACK_SAMPLE_RATES = { 48_000f, 44_100f };
    private static final int[] FALLBACK_SAMPLE_SIZES_IN_BITS = { 32, 24, 16 };
    private static final long CONTROL_POLL_MILLIS = 200L;
    private static final long PROGRESS_REFRESH_NANOS = TimeUnit.SECONDS.toNanos(1L);

    private JavaSoundPlaybackDemo() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.in, System.out, System.err, Path.of("."));
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(
            String[] args,
            InputStream standardInput,
            PrintStream output,
            PrintStream errorOutput,
            Path workingDirectory
    ) {
        return run(
                args,
                standardInput,
                output,
                errorOutput,
                workingDirectory,
                JavaSoundPlaybackTui::run
        );
    }

    static int run(
            String[] args,
            InputStream standardInput,
            PrintStream output,
            PrintStream errorOutput,
            Path workingDirectory,
            TuiRunner tuiRunner
    ) {
        return run(
                args,
                standardInput,
                output,
                errorOutput,
                workingDirectory,
                tuiRunner,
                JavaSoundPlaybackSession::new
        );
    }

    static int run(
            String[] args,
            InputStream standardInput,
            PrintStream output,
            PrintStream errorOutput,
            Path workingDirectory,
            TuiRunner tuiRunner,
            PlaybackSessionFactory sessionFactory
    ) {
        final PlaybackArguments options;
        try {
            options = PlaybackArguments.parse(args);
        } catch (IllegalArgumentException error) {
            errorOutput.println("Argument error: " + error.getMessage());
            errorOutput.println(PlaybackArguments.usage());
            return 2;
        }

        if (options.help()) {
            output.println(PlaybackArguments.usage());
            output.println("TUI keys: O open, Space/P play-pause, S stop, J/L seek, Q/Esc quit.");
            output.println("When the timeline has focus: arrows, Page Up/Down, Home, and End seek.");
            output.println();
            output.println("Commands available with --console:");
            output.println(ConsolePlaybackCommand.helpText());
            return 0;
        }

        if (options.mode() == PlaybackArguments.Mode.AUTO
                || options.mode() == PlaybackArguments.Mode.TUI) {
            try {
                return tuiRunner.run(options, workingDirectory);
            } catch (TerminalUnavailableException unavailable) {
                if (options.mode() == PlaybackArguments.Mode.TUI) {
                    errorOutput.println("Native terminal unavailable: " + unavailable.getMessage());
                    errorOutput.println("The TUI deliberately does not open an AWT/Swing emulator.");
                    errorOutput.println("Use --console or launch jflac-playback directly from a real terminal.");
                    return 1;
                }
                errorOutput.println(
                        "Native terminal unavailable; using the line-command interface. "
                                + unavailable.getMessage()
                );
            } catch (IllegalArgumentException error) {
                errorOutput.println("Input error: " + error.getMessage());
                return 2;
            } catch (Exception error) {
                errorOutput.println("Terminal playback failed: " + usefulMessage(error));
                errorOutput.println("Try --console for the line-command interface.");
                return 1;
            }
        }

        BufferedReader console = new BufferedReader(
                new InputStreamReader(standardInput, StandardCharsets.UTF_8)
        );

        Path input = options.input();
        try {
            if (input == null) {
                if (!options.controlsEnabled()) {
                    throw new IllegalArgumentException("--no-controls requires an input file argument.");
                }
                InitialSelection selection = promptForMusicFile(console, output, workingDirectory);
                if (selection.quit()) {
                    return 0;
                }
                input = selection.input();
                if (input == null) {
                    errorOutput.println("No music file was selected before console input ended.");
                    return 2;
                }
            } else {
                input = validateInputPath(input);
            }

            return runPlaybackLoop(input, options, console, output, workingDirectory, sessionFactory);
        } catch (IllegalArgumentException error) {
            errorOutput.println("Input error: " + error.getMessage());
            return 2;
        } catch (Exception error) {
            errorOutput.println("Playback failed: " + usefulMessage(error));
            return 1;
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
        BlockingQueue<ConsoleEvent> events = new LinkedBlockingQueue<>();
        boolean controlsAvailable = options.controlsEnabled();
        if (controlsAvailable) {
            startConsoleInputPump(console, events);
        }

        Path currentInput = firstInput;
        long nextStartMicros = options.startMicros();
        boolean nextStartsPaused = options.startPaused();
        int progressWidth = 0;

        while (true) {
            Path replacementInput = null;
            try (PlaybackSession playback = sessionFactory.open(currentInput)) {
                output.println("Opening through Java Sound: " + currentInput);
                output.println("Decoded format:  " + describe(playback.sourceFormat()));
                if (!sameFormat(playback.sourceFormat(), playback.playbackFormat())) {
                    output.println("Playback format: " + describe(playback.playbackFormat()));
                }
                if (controlsAvailable) {
                    output.println("Enter 'help' for controls. Commands remain available during playback.");
                }

                playback.start(nextStartMicros, nextStartsPaused);
                nextStartMicros = 0L;
                nextStartsPaused = false;

                boolean awaitingFileChoice = false;
                List<Path> menuCandidates = List.of();
                long nextProgressRefresh = 0L;

                while (true) {
                    PlaybackSession.Snapshot snapshot = playback.snapshot();
                    if (snapshot.state() == PlaybackSession.State.FAILED) {
                        clearProgressLine(output, progressWidth);
                        throw new IOException("Audio worker failed: " + usefulMessage(snapshot.failure()), snapshot.failure());
                    }
                    if (snapshot.state() == PlaybackSession.State.ENDED) {
                        progressWidth = printProgress(output, snapshot, progressWidth);
                        clearProgressLine(output, progressWidth);
                        progressWidth = 0;
                        output.println("Playback finished: " + currentInput.getFileName());
                        return 0;
                    }

                    ConsoleEvent event = controlsAvailable
                            ? events.poll(CONTROL_POLL_MILLIS, TimeUnit.MILLISECONDS)
                            : null;
                    if (!controlsAvailable) {
                        Thread.sleep(CONTROL_POLL_MILLIS);
                    }

                    if (event != null) {
                        clearProgressLine(output, progressWidth);
                        progressWidth = 0;

                        if (event.endOfInput()) {
                            controlsAvailable = false;
                            if (event.failure() != null) {
                                output.println("Console input stopped: " + usefulMessage(event.failure()));
                            } else {
                                output.println("Console input ended; playback will continue to the end.");
                            }
                            if (playback.isPauseRequested()) {
                                playback.resume();
                                output.println("Playback resumed because no further console command can arrive.");
                            }
                            continue;
                        }

                        String line = event.line();
                        if (awaitingFileChoice) {
                            String selection = line == null ? "" : line.trim();
                            if ("q".equalsIgnoreCase(selection)
                                    || "quit".equalsIgnoreCase(selection)
                                    || "exit".equalsIgnoreCase(selection)) {
                                output.println("Playback stopped.");
                                return 0;
                            }
                            if ("c".equalsIgnoreCase(selection) || "cancel".equalsIgnoreCase(selection)) {
                                awaitingFileChoice = false;
                                output.println("File selection cancelled; current playback continues.");
                                continue;
                            }
                            if ("h".equalsIgnoreCase(selection) || "help".equalsIgnoreCase(selection)) {
                                output.println("Enter a listed number, a file path, 'cancel', or 'q'.");
                                continue;
                            }

                            try {
                                replacementInput = resolveMusicChoice(line, menuCandidates);
                                replacementInput = validateInputPath(replacementInput);
                                break;
                            } catch (IllegalArgumentException selectionError) {
                                output.println("Selection error: " + selectionError.getMessage());
                                printMusicMenu(output, menuCandidates);
                                continue;
                            }
                        }

                        final ConsolePlaybackCommand command;
                        try {
                            command = ConsolePlaybackCommand.parse(line);
                        } catch (IllegalArgumentException commandError) {
                            output.println("Command error: " + commandError.getMessage());
                            continue;
                        }

                        try {
                            switch (command.type()) {
                                case TOGGLE_PAUSE -> playback.togglePause();
                                case PAUSE -> playback.pause();
                                case RESUME -> playback.resume();
                                case SEEK_ABSOLUTE -> {
                                    playback.seekToMicros(command.timeMicros());
                                    output.println("Seeking to "
                                            + PlaybackArguments.formatTime(command.timeMicros()) + "...");
                                }
                                case SEEK_RELATIVE -> {
                                    playback.seekByMicros(command.timeMicros());
                                    String direction = command.timeMicros() < 0L ? "backwards" : "forwards";
                                    output.println("Seeking " + direction + " by "
                                            + PlaybackArguments.formatTime(Math.abs(command.timeMicros())) + "...");
                                }
                                case STATUS -> output.println(renderProgress(playback.snapshot()));
                                case LIST -> {
                                    menuCandidates = listMusicFiles(workingDirectory);
                                    printMusicMenu(output, menuCandidates);
                                }
                                case OPEN -> {
                                    if (command.argument() == null) {
                                        menuCandidates = listMusicFiles(workingDirectory);
                                        printMusicMenu(output, menuCandidates);
                                        output.println("Enter a number or file path ('cancel' keeps this track):");
                                        awaitingFileChoice = true;
                                    } else {
                                        replacementInput = validateInputPath(Path.of(command.argument()).normalize());
                                    }
                                }
                                case HELP -> output.println(ConsolePlaybackCommand.helpText());
                                case QUIT -> {
                                    output.println("Playback stopped.");
                                    return 0;
                                }
                            }
                        } catch (IllegalArgumentException | IllegalStateException commandError) {
                            output.println("Command error: " + commandError.getMessage());
                            continue;
                        }

                        if (replacementInput != null) {
                            break;
                        }
                    }

                    long now = System.nanoTime();
                    if (now >= nextProgressRefresh) {
                        progressWidth = printProgress(output, playback.snapshot(), progressWidth);
                        nextProgressRefresh = now + PROGRESS_REFRESH_NANOS;
                    }
                }
            } finally {
                clearProgressLine(output, progressWidth);
                progressWidth = 0;
            }

            currentInput = replacementInput;
        }
    }

    private static void startConsoleInputPump(BufferedReader console, BlockingQueue<ConsoleEvent> events) {
        Thread inputThread = new Thread(() -> {
            try {
                String line;
                while ((line = console.readLine()) != null) {
                    events.offer(new ConsoleEvent(line, false, null));
                }
                events.offer(new ConsoleEvent(null, true, null));
            } catch (IOException error) {
                events.offer(new ConsoleEvent(null, true, error));
            }
        }, "jflac-console-input");
        inputThread.setDaemon(true);
        inputThread.start();
    }

    private static InitialSelection promptForMusicFile(
            BufferedReader console,
            PrintStream output,
            Path workingDirectory
    )
            throws IOException {
        List<Path> candidates = listMusicFiles(workingDirectory);
        while (true) {
            printMusicMenu(output, candidates);
            output.print("Enter a number or file path (q to quit): ");
            output.flush();

            String choice = console.readLine();
            if (choice == null) {
                return new InitialSelection(null, false);
            }
            if ("q".equalsIgnoreCase(choice.trim())) {
                return new InitialSelection(null, true);
            }

            try {
                return new InitialSelection(validateInputPath(resolveMusicChoice(choice, candidates)), false);
            } catch (IllegalArgumentException error) {
                output.println("Selection error: " + error.getMessage());
            }
        }
    }

    static List<Path> listMusicFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        try (var paths = Files.list(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(JavaSoundPlaybackDemo::hasSupportedMusicExtension)
                    .map(Path::normalize)
                    .sorted(Comparator.comparing(
                            path -> path.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER
                    ))
                    .toList();
        }
    }

    static boolean hasSupportedMusicExtension(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".flac") || name.endsWith(".oga") || name.endsWith(".ogg");
    }

    static Path resolveMusicChoice(String rawChoice, List<Path> candidates) {
        String choice = PlaybackArguments.stripOuterQuotes(rawChoice == null ? "" : rawChoice.trim()).trim();
        if (choice.isEmpty()) {
            throw new IllegalArgumentException("Enter a file number or path.");
        }

        if (choice.chars().allMatch(Character::isDigit)) {
            try {
                int number = Integer.parseInt(choice);
                if (number >= 1 && number <= candidates.size()) {
                    return candidates.get(number - 1);
                }
            } catch (NumberFormatException ignored) {
                // The generic range message below also covers very large numbers.
            }
            throw new IllegalArgumentException("File number must be between 1 and " + candidates.size() + ".");
        }

        return Path.of(choice).normalize();
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

    static Path validateInputPath(Path input) {
        if (input == null) {
            throw new IllegalArgumentException("Input music file must not be null.");
        }
        Path normalised = input.normalize();
        if (!Files.isRegularFile(normalised)) {
            throw new IllegalArgumentException("Input music file does not exist: " + normalised);
        }
        return normalised;
    }

    static AudioFormat selectPlaybackFormat(AudioFormat decodedFormat, PlaybackSupport playbackSupport)
            throws LineUnavailableException {
        for (AudioFormat candidate : playbackFormatCandidates(decodedFormat)) {
            boolean originalFormat = sameFormat(decodedFormat, candidate);
            if (playbackSupport.isLineSupported(candidate)
                    && (originalFormat || playbackSupport.isConversionSupported(candidate, decodedFormat))) {
                return candidate;
            }
        }

        throw new LineUnavailableException(
                "No output line supports decoded PCM format or the standard fallback formats: "
                        + describe(decodedFormat)
        );
    }

    private static List<AudioFormat> playbackFormatCandidates(AudioFormat decodedFormat) {
        List<AudioFormat> candidates = new ArrayList<>();
        candidates.add(decodedFormat);

        /*
         * Keep the sample rate before changing sample size. Resampling is the
         * larger quality and compatibility step, so preserving source rate is
         * preferred whenever the mixer accepts it.
         */
        addPcmCandidates(candidates, decodedFormat.getSampleRate(), decodedFormat.getChannels());
        for (float sampleRate : FALLBACK_SAMPLE_RATES) {
            addPcmCandidates(candidates, sampleRate, decodedFormat.getChannels());
        }
        return candidates;
    }

    private static void addPcmCandidates(List<AudioFormat> candidates, float sampleRate, int channels) {
        for (int bitsPerSample : FALLBACK_SAMPLE_SIZES_IN_BITS) {
            addIfAbsent(candidates, signedPcm(sampleRate, bitsPerSample, channels));
        }
    }

    private static void addIfAbsent(List<AudioFormat> candidates, AudioFormat candidate) {
        for (AudioFormat existing : candidates) {
            if (sameFormat(existing, candidate)) {
                return;
            }
        }
        candidates.add(candidate);
    }

    private static AudioFormat signedPcm(float sampleRate, int bitsPerSample, int channels) {
        int bytesPerSample = (bitsPerSample + 7) / 8;
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                bitsPerSample,
                channels,
                channels * bytesPerSample,
                sampleRate,
                false
        );
    }

    static SourceDataLine openSourceLine(AudioFormat format) throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("No output line supports decoded PCM format: " + describe(format));
        }

        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format);
        return line;
    }

    interface PlaybackSupport {
        boolean isLineSupported(AudioFormat format);

        boolean isConversionSupported(AudioFormat target, AudioFormat source);
    }

    enum SystemPlaybackSupport implements PlaybackSupport {
        INSTANCE;

        @Override
        public boolean isLineSupported(AudioFormat format) {
            return AudioSystem.isLineSupported(new DataLine.Info(SourceDataLine.class, format));
        }

        @Override
        public boolean isConversionSupported(AudioFormat target, AudioFormat source) {
            return AudioSystem.isConversionSupported(target, source);
        }
    }

    static boolean sameFormat(AudioFormat left, AudioFormat right) {
        return left.getEncoding().equals(right.getEncoding())
                && left.getSampleRate() == right.getSampleRate()
                && left.getSampleSizeInBits() == right.getSampleSizeInBits()
                && left.getChannels() == right.getChannels()
                && left.getFrameSize() == right.getFrameSize()
                && left.getFrameRate() == right.getFrameRate()
                && left.isBigEndian() == right.isBigEndian();
    }

    static String describe(AudioFormat format) {
        return String.format(
                "%.0f Hz, %d channel(s), %d bit, frame size %d byte(s), %s-endian, %s",
                format.getSampleRate(),
                format.getChannels(),
                format.getSampleSizeInBits(),
                format.getFrameSize(),
                format.isBigEndian() ? "big" : "little",
                format.getEncoding()
        );
    }

    static String renderProgress(PlaybackSession.Snapshot snapshot) {
        String state = snapshot.state().name().toLowerCase(Locale.ROOT);
        String position = PlaybackArguments.formatTime(snapshot.positionMicros());
        String duration = snapshot.durationMicros() < 0L
                ? "--:--"
                : PlaybackArguments.formatTime(snapshot.durationMicros());
        String percentage = "";
        if (snapshot.durationMicros() > 0L) {
            long boundedPosition = Math.min(snapshot.positionMicros(), snapshot.durationMicros());
            long percent = Math.round((boundedPosition * 100.0) / snapshot.durationMicros());
            percentage = String.format("  %3d%%", percent);
        }
        return String.format(
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

    static String usefulMessage(Throwable error) {
        if (error == null) {
            return "unknown failure";
        }
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record ConsoleEvent(String line, boolean endOfInput, Throwable failure) {
    }

    private record InitialSelection(Path input, boolean quit) {
    }

    @FunctionalInterface
    interface TuiRunner {
        int run(PlaybackArguments options, Path workingDirectory) throws Exception;
    }
}
