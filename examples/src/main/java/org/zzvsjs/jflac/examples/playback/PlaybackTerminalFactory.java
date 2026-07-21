package org.zzvsjs.jflac.examples.playback;

import com.googlecode.lanterna.terminal.Terminal;

import java.io.IOException;

/** Creates the terminal used by the playback TUI. */
@FunctionalInterface
interface PlaybackTerminalFactory {
    Terminal open() throws IOException;
}
