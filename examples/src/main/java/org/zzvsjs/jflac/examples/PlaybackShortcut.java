package org.zzvsjs.jflac.examples;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

/** Pure mapping from a terminal keystroke to a player-level action. */
enum PlaybackShortcut {
    NONE,
    OPEN,
    TOGGLE_PLAY_PAUSE,
    STOP,
    SEEK_BACKWARDS,
    SEEK_FORWARDS,
    HELP,
    QUIT;

    static PlaybackShortcut from(KeyStroke keyStroke, boolean buttonHasFocus) {
        if (keyStroke.isAltDown() || keyStroke.isCtrlDown()) {
            return NONE;
        }
        if (keyStroke.getKeyType() == KeyType.Escape) {
            return QUIT;
        }
        if (keyStroke.getKeyType() != KeyType.Character || keyStroke.getCharacter() == null) {
            return NONE;
        }

        return switch (Character.toLowerCase(keyStroke.getCharacter())) {
            case 'o' -> OPEN;
            case 'p' -> TOGGLE_PLAY_PAUSE;
            case 's' -> STOP;
            case 'j' -> SEEK_BACKWARDS;
            case 'l' -> SEEK_FORWARDS;
            case 'q' -> QUIT;
            case '?' -> HELP;
            case ' ' -> buttonHasFocus ? NONE : TOGGLE_PLAY_PAUSE;
            default -> NONE;
        };
    }
}

