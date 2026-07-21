package org.zzvsjs.jflac.examples.playback;

import java.io.IOException;

/**
 * Signals that no interactive operating-system terminal can host the TUI.
 *
 * <p>This is deliberately distinct from an arbitrary terminal I/O failure.
 * The automatic launcher may safely fall back to the line-command interface
 * only when terminal acquisition fails before the player opens an audio
 * session. Once the screen has started, an error must be reported instead of
 * silently starting a second frontend.</p>
 */
final class TerminalUnavailableException extends IOException {
    TerminalUnavailableException(String message) {
        super(message);
    }
}
