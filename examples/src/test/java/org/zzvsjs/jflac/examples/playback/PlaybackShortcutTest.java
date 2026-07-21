package org.zzvsjs.jflac.examples.playback;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class PlaybackShortcutTest {
    @Test
    public void characterShortcutsAreCaseInsensitive() {
        assertEquals(PlaybackShortcut.OPEN, shortcut('O'));
        assertEquals(PlaybackShortcut.TOGGLE_PLAY_PAUSE, shortcut('p'));
        assertEquals(PlaybackShortcut.STOP, shortcut('S'));
        assertEquals(PlaybackShortcut.SEEK_BACKWARDS, shortcut('j'));
        assertEquals(PlaybackShortcut.SEEK_FORWARDS, shortcut('L'));
        assertEquals(PlaybackShortcut.HELP, shortcut('?'));
        assertEquals(PlaybackShortcut.QUIT, shortcut('q'));
    }

    @Test
    public void escapeQuitsAndUnknownOrModifiedKeysPassThrough() {
        assertEquals(
                PlaybackShortcut.QUIT,
                PlaybackShortcut.from(new KeyStroke(KeyType.Escape), false)
        );
        assertEquals(PlaybackShortcut.NONE, shortcut('x'));
        assertEquals(
                PlaybackShortcut.NONE,
                PlaybackShortcut.from(new KeyStroke('q', true, false), false)
        );
        assertEquals(
                PlaybackShortcut.NONE,
                PlaybackShortcut.from(new KeyStroke(KeyType.Tab), false)
        );
    }

    @Test
    public void controlCQuitsWhenRawTerminalModeDisablesSignals() {
        assertEquals(
                PlaybackShortcut.QUIT,
                PlaybackShortcut.from(new KeyStroke('c', true, false), false)
        );
    }

    @Test
    public void spaceRemainsAButtonActivationWhenAButtonHasFocus() {
        KeyStroke space = new KeyStroke(' ', false, false);

        assertEquals(PlaybackShortcut.TOGGLE_PLAY_PAUSE, PlaybackShortcut.from(space, false));
        assertEquals(PlaybackShortcut.NONE, PlaybackShortcut.from(space, true));
    }

    private static PlaybackShortcut shortcut(char character) {
        return PlaybackShortcut.from(new KeyStroke(character, false, false), false);
    }
}
