package org.zzvsjs.jflac.examples;

import com.googlecode.lanterna.TerminalSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.function.Consumer;

/**
 * Small, platform-neutral boundary between Lanterna and a system terminal.
 *
 * <p>Keeping this interface narrow makes the terminal bridge testable without
 * a real console and keeps Win32/POSIX details out of the playback UI. A future
 * backend can replace JLine without changing the file chooser, buttons, or
 * seek-bar code.</p>
 */
interface PlaybackTerminalBackend extends AutoCloseable {
    InputStream input();

    OutputStream output();

    Charset encoding();

    TerminalSize size();

    void setResizeListener(Consumer<TerminalSize> listener);

    /**
     * Returns whether mouse reporting must be enabled through the native
     * console API rather than only through terminal escape sequences.
     */
    boolean usesNativeMouseInput();

    void setNativeMouseTracking(boolean enabled) throws IOException;

    @Override
    void close() throws IOException;
}
