package org.zzvsjs.jflac.examples.playback;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class ConsolePlaybackCommandTest {
    @Test
    public void parsesPlaybackStateCommands() {
        assertEquals(ConsolePlaybackCommand.Type.TOGGLE_PAUSE, ConsolePlaybackCommand.parse("p").type());
        assertEquals(ConsolePlaybackCommand.Type.PAUSE, ConsolePlaybackCommand.parse("pause").type());
        assertEquals(ConsolePlaybackCommand.Type.RESUME, ConsolePlaybackCommand.parse("resume").type());
        assertEquals(ConsolePlaybackCommand.Type.QUIT, ConsolePlaybackCommand.parse("exit").type());
    }

    @Test
    public void parsesAbsoluteAndRelativeSeekCommands() {
        ConsolePlaybackCommand absolute = ConsolePlaybackCommand.parse("seek 01:30");
        ConsolePlaybackCommand forwards = ConsolePlaybackCommand.parse("+10.5");
        ConsolePlaybackCommand backwards = ConsolePlaybackCommand.parse("-5");

        assertEquals(ConsolePlaybackCommand.Type.SEEK_ABSOLUTE, absolute.type());
        assertEquals(90_000_000L, absolute.timeMicros());
        assertEquals(ConsolePlaybackCommand.Type.SEEK_RELATIVE, forwards.type());
        assertEquals(10_500_000L, forwards.timeMicros());
        assertEquals(-5_000_000L, backwards.timeMicros());
    }

    @Test
    public void openPreservesAPathContainingSpaces() {
        ConsolePlaybackCommand command = ConsolePlaybackCommand.parse("open \"folder with spaces/song.flac\"");

        assertEquals(ConsolePlaybackCommand.Type.OPEN, command.type());
        assertEquals("folder with spaces/song.flac", command.argument());
    }

    @Test
    public void openWithoutPathRequestsMenuSelection() {
        ConsolePlaybackCommand command = ConsolePlaybackCommand.parse("open");

        assertEquals(ConsolePlaybackCommand.Type.OPEN, command.type());
        assertNull(command.argument());
    }

    @Test
    public void emptyLineRequestsStatus() {
        assertEquals(ConsolePlaybackCommand.Type.STATUS, ConsolePlaybackCommand.parse("  ").type());
    }

    @Test
    public void missingSeekTargetAndUnknownCommandsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ConsolePlaybackCommand.parse("seek"));
        assertThrows(IllegalArgumentException.class, () -> ConsolePlaybackCommand.parse("volume 50"));
    }
}
