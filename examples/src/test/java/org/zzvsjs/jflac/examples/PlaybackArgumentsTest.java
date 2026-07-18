package org.zzvsjs.jflac.examples;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class PlaybackArgumentsTest {
    @Test
    public void noArgumentsEntersInteractiveFileSelection() {
        PlaybackArguments arguments = PlaybackArguments.parse(new String[0]);

        assertNull(arguments.input());
        assertEquals(0L, arguments.startMicros());
        assertFalse(arguments.startPaused());
        assertTrue(arguments.controlsEnabled());
        assertEquals(PlaybackArguments.Mode.AUTO, arguments.mode());
    }

    @Test
    public void positionalFileAndOptionsAreParsed() {
        PlaybackArguments arguments = PlaybackArguments.parse(new String[] {
                "music file.flac",
                "--start",
                "01:30.5",
                "--paused"
        });

        assertEquals(Path.of("music file.flac"), arguments.input());
        assertEquals(90_500_000L, arguments.startMicros());
        assertTrue(arguments.startPaused());
    }

    @Test
    public void namedFileAndEqualsStartAreParsed() {
        PlaybackArguments arguments = PlaybackArguments.parse(new String[] {
                "--file=music.flac",
                "--start=1:02:03.5",
                "--no-controls"
        });

        assertEquals(Path.of("music.flac"), arguments.input());
        assertEquals(3_723_500_000L, arguments.startMicros());
        assertFalse(arguments.controlsEnabled());
        assertEquals(PlaybackArguments.Mode.HEADLESS, arguments.mode());
    }

    @Test
    public void outerQuotesAreRemovedFromFileValue() {
        PlaybackArguments arguments = PlaybackArguments.parse(new String[] {
                "--file",
                "\"folder with spaces/music.flac\""
        });

        assertEquals(Path.of("folder with spaces", "music.flac"), arguments.input());
    }

    @Test
    public void duplicateFileArgumentsAreRejected() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> PlaybackArguments.parse(new String[] { "first.flac", "--file", "second.flac" })
        );

        assertEquals("Specify only one input music file.", error.getMessage());
    }

    @Test
    public void pausedNonInteractivePlaybackIsRejected() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> PlaybackArguments.parse(new String[] { "music.flac", "--paused", "--no-controls" })
        );

        assertEquals("--paused cannot be combined with --no-controls.", error.getMessage());
    }

    @Test
    public void consoleModeRetainsInteractiveLineControls() {
        PlaybackArguments arguments = PlaybackArguments.parse(new String[] { "--console", "music.flac" });

        assertEquals(PlaybackArguments.Mode.CONSOLE, arguments.mode());
        assertTrue(arguments.controlsEnabled());
    }

    @Test
    public void tuiOptionRequiresTheNativeFrontend() {
        PlaybackArguments arguments = PlaybackArguments.parse(new String[] { "--tui", "music.flac" });

        assertEquals(PlaybackArguments.Mode.TUI, arguments.mode());
        assertTrue(arguments.controlsEnabled());
    }

    @Test
    public void consoleAndHeadlessModesAreMutuallyExclusiveInEitherOrder() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PlaybackArguments.parse(new String[] { "--console", "--no-controls" })
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> PlaybackArguments.parse(new String[] { "--no-controls", "--console" })
        );
    }

    @Test
    public void forcedTuiIsMutuallyExclusiveWithOtherFrontends() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PlaybackArguments.parse(new String[] { "--tui", "--console" })
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> PlaybackArguments.parse(new String[] { "--console", "--tui" })
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> PlaybackArguments.parse(new String[] { "--tui", "--no-controls" })
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> PlaybackArguments.parse(new String[] { "--no-controls", "--tui" })
        );
    }

    @Test
    public void timeParserAcceptsSecondsAndClockForms() {
        assertEquals(90_250_000L, PlaybackArguments.parseTimeMicros("90.25"));
        assertEquals(90_250_000L, PlaybackArguments.parseTimeMicros("01:30.25"));
        assertEquals(3_723_250_000L, PlaybackArguments.parseTimeMicros("1:02:03.25"));
    }

    @Test
    public void timeParserRejectsInvalidOrOverflowingValues() {
        assertThrows(IllegalArgumentException.class, () -> PlaybackArguments.parseTimeMicros("-1"));
        assertThrows(IllegalArgumentException.class, () -> PlaybackArguments.parseTimeMicros("1:60"));
        assertThrows(IllegalArgumentException.class, () -> PlaybackArguments.parseTimeMicros("1:60:00"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PlaybackArguments.parseTimeMicros("999999999999999999:00:00")
        );
    }

    @Test
    public void timeFormatterUsesMinuteAndHourForms() {
        assertEquals("00:00", PlaybackArguments.formatTime(0L));
        assertEquals("01:30", PlaybackArguments.formatTime(90_999_999L));
        assertEquals("1:02:03", PlaybackArguments.formatTime(3_723_000_000L));
    }
}
