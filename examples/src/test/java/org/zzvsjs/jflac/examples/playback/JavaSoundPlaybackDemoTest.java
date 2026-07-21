package org.zzvsjs.jflac.examples.playback;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class JavaSoundPlaybackDemoTest {
    @Test
    public void helpDoesNotOpenAnAudioOrTerminalDevice() throws Exception {
        ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(
                outputBytes,
                true,
                StandardCharsets.UTF_8
        );
             PrintStream error = new PrintStream(
                     errorBytes,
                     true,
                     StandardCharsets.UTF_8
             )) {
            assertEquals(
                    0,
                    JavaSoundPlaybackDemo.run(
                            new String[] { "--help" },
                            new ByteArrayInputStream(new byte[0]),
                            output,
                            error,
                            Path.of(".")
                    )
            );
        }

        assertTrue(
                outputBytes.toString(StandardCharsets.UTF_8)
                        .contains("Usage: jflac-playback")
        );
        assertEquals("", errorBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void defaultModeDispatchesToTuiWithoutOpeningAudioInTheLauncher() throws Exception {
        AtomicReference<PlaybackArguments> launchedOptions = new AtomicReference<>();
        AtomicReference<Path> launchedDirectory = new AtomicReference<>();
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();

        try (PrintStream error = new PrintStream(
                errorBytes,
                true,
                StandardCharsets.UTF_8
        )) {
            int result = JavaSoundPlaybackDemo.run(
                    new String[] { "--file", "music.flac", "--start", "12.5", "--paused" },
                    new ByteArrayInputStream(new byte[0]),
                    System.out,
                    error,
                    Path.of("demo-directory"),
                    (options, workingDirectory) -> {
                        launchedOptions.set(options);
                        launchedDirectory.set(workingDirectory);
                        return 23;
                    }
            );

            assertEquals(23, result);
        }

        Path expectedDirectory = Path.of("demo-directory").toAbsolutePath().normalize();
        assertEquals(expectedDirectory.resolve("music.flac"), launchedOptions.get().input());
        assertEquals(12_500_000L, launchedOptions.get().startMicros());
        assertTrue(launchedOptions.get().startPaused());
        assertEquals(PlaybackArguments.Mode.AUTO, launchedOptions.get().mode());
        assertEquals(expectedDirectory, launchedDirectory.get());
        assertEquals("", errorBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void automaticModeFallsBackOnlyWhenNoNativeTerminalIsAvailable() throws Exception {
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
        try (PrintStream error = new PrintStream(
                errorBytes,
                true,
                StandardCharsets.UTF_8
        )) {
            int result = JavaSoundPlaybackDemo.run(
                    new String[0],
                    new ByteArrayInputStream("q\n".getBytes(StandardCharsets.UTF_8)),
                    System.out,
                    error,
                    Path.of("."),
                    (options, workingDirectory) -> {
                        throw new TerminalUnavailableException(
                                "test terminal is unavailable"
                        );
                    }
            );

            assertEquals(0, result);
        }

        assertTrue(
                errorBytes.toString(StandardCharsets.UTF_8)
                        .contains("using the line-command interface")
        );
    }

    @Test
    public void forcedTuiDoesNotFallBackToLineCommands() throws Exception {
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
        try (PrintStream error = new PrintStream(
                errorBytes,
                true,
                StandardCharsets.UTF_8
        )) {
            int result = JavaSoundPlaybackDemo.run(
                    new String[] { "--tui" },
                    new ByteArrayInputStream("q\n".getBytes(StandardCharsets.UTF_8)),
                    System.out,
                    error,
                    Path.of("."),
                    (options, workingDirectory) -> {
                        throw new TerminalUnavailableException(
                                "test terminal is unavailable"
                        );
                    }
            );

            assertEquals(1, result);
        }

        String errorText = errorBytes.toString(StandardCharsets.UTF_8);
        assertTrue(errorText.contains("Native terminal unavailable"));
        assertTrue(errorText.contains("does not open an AWT/Swing emulator"));
    }

    @Test
    public void automaticModeDoesNotHideOrdinaryTuiFailures() throws Exception {
        InputStream unreadableInput = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError(
                        "Ordinary TUI failures must not enter the console loop"
                );
            }
        };
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
        try (PrintStream error = new PrintStream(
                errorBytes,
                true,
                StandardCharsets.UTF_8
        )) {
            int result = JavaSoundPlaybackDemo.run(
                    new String[0],
                    unreadableInput,
                    System.out,
                    error,
                    Path.of("."),
                    (options, workingDirectory) -> {
                        throw new java.io.IOException("test TUI failure");
                    }
            );

            assertEquals(1, result);
        }

        String errorText = errorBytes.toString(StandardCharsets.UTF_8);
        assertTrue(errorText.contains("Terminal playback failed: test TUI failure"));
        assertTrue(errorText.contains("Try --console"));
    }

    @Test
    public void providerLinkageErrorsUseTheStructuredTuiFailurePath() throws Exception {
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();

        try (PrintStream error = new PrintStream(
                errorBytes,
                true,
                StandardCharsets.UTF_8
        )) {
            int result = JavaSoundPlaybackDemo.run(
                    new String[0],
                    new ByteArrayInputStream(new byte[0]),
                    System.out,
                    error,
                    Path.of("."),
                    (options, workingDirectory) -> {
                        throw new LinkageError("test provider linkage failure");
                    }
            );

            assertEquals(JavaSoundPlaybackDemo.EXIT_FAILURE, result);
        }

        assertTrue(
                errorBytes.toString(StandardCharsets.UTF_8)
                        .contains("Terminal playback failed: test provider linkage failure")
        );
    }
}
