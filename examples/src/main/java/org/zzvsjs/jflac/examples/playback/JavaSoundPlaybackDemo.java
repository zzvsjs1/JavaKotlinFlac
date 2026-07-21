package org.zzvsjs.jflac.examples.playback;

import java.io.IOError;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Starts the FLAC/Ogg FLAC terminal player built on the jflac Java Sound SPI.
 *
 * <p>This class is intentionally limited to command-line parsing and frontend
 * selection. Console interaction, file handling, audio-device negotiation, and
 * playback state each live in focused package-private collaborators.</p>
 */
public final class JavaSoundPlaybackDemo {
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_FAILURE = 1;
    static final int EXIT_INVALID_INPUT = 2;

    private JavaSoundPlaybackDemo() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.in, System.out, System.err, Path.of("."));
        if (exitCode != EXIT_SUCCESS) {
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
        Objects.requireNonNull(standardInput, "standardInput");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(errorOutput, "errorOutput");
        Objects.requireNonNull(tuiRunner, "tuiRunner");
        Objects.requireNonNull(sessionFactory, "sessionFactory");

        final PlaybackArguments parsedOptions;
        try {
            parsedOptions = PlaybackArguments.parse(args);
        } catch (IllegalArgumentException error) {
            errorOutput.println("Argument error: " + error.getMessage());
            errorOutput.println(PlaybackArguments.usage());
            return EXIT_INVALID_INPUT;
        }

        if (parsedOptions.help()) {
            printHelp(output);
            return EXIT_SUCCESS;
        }

        /*
         * Resolve relative command-line paths once, before choosing a frontend.
         * This gives the TUI, console mode, and injected test launchers the same
         * working-directory contract even when the JVM process directory is
         * different.
         */
        Path effectiveWorkingDirectory = Objects.requireNonNull(
                workingDirectory,
                "workingDirectory"
        ).toAbsolutePath().normalize();
        PlaybackArguments options = parsedOptions.withInput(
                PlaybackFiles.resolveAgainstWorkingDirectory(
                        parsedOptions.input(),
                        effectiveWorkingDirectory
                )
        );

        if (options.mode() == PlaybackArguments.Mode.AUTO
                || options.mode() == PlaybackArguments.Mode.TUI) {
            try {
                return tuiRunner.run(options, effectiveWorkingDirectory);
            } catch (TerminalUnavailableException unavailable) {
                if (options.mode() == PlaybackArguments.Mode.TUI) {
                    errorOutput.println("Native terminal unavailable: " + unavailable.getMessage());
                    errorOutput.println("The TUI deliberately does not open an AWT/Swing emulator.");
                    errorOutput.println("Use --console or launch jflac-playback directly from a real terminal.");
                    return EXIT_FAILURE;
                }

                errorOutput.println(
                        "Native terminal unavailable; using the line-command interface. "
                                + unavailable.getMessage()
                );
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                errorOutput.println("Terminal playback was interrupted.");
                return EXIT_FAILURE;
            } catch (Exception | IOError | LinkageError error) {
                errorOutput.println(
                        "Terminal playback failed: " + PlaybackMessages.rootCauseMessage(error)
                );
                errorOutput.println("Try --console for the line-command interface.");
                return EXIT_FAILURE;
            }
        }

        return ConsolePlaybackRunner.run(
                options,
                standardInput,
                output,
                errorOutput,
                effectiveWorkingDirectory,
                sessionFactory
        );
    }

    private static void printHelp(PrintStream output) {
        output.println(PlaybackArguments.usage());
        output.println("TUI keys: O open, Space/P play-pause, S stop, J/L seek, Q/Esc quit.");
        output.println("When the timeline has focus: arrows, Page Up/Down, Home, and End seek.");
        output.println();
        output.println("Commands available with --console:");
        output.println(ConsolePlaybackCommand.helpText());
    }

    @FunctionalInterface
    interface TuiRunner {
        int run(PlaybackArguments options, Path workingDirectory) throws Exception;
    }
}
