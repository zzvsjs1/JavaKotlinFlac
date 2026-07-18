package org.zzvsjs.jflac.examples;

import com.googlecode.lanterna.TerminalSize;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/** JLine-backed access to the process' real operating-system terminal. */
final class JLineTerminalBackend implements PlaybackTerminalBackend {
    private static final String TERMINAL_NAME = "jflac Java Sound Playback";
    private static final String UNAVAILABLE_MESSAGE =
            "No interactive system terminal is attached. Run the jflac-playback "
                    + "launcher directly from Windows Terminal or another real terminal, "
                    + "or use --console.";

    private final Terminal terminal;
    private final boolean nativeMouseInput;

    private JLineTerminalBackend(Terminal terminal, boolean nativeMouseInput) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.nativeMouseInput = nativeMouseInput;
    }

    static JLineTerminalBackend open() throws TerminalUnavailableException {
        Terminal opened = null;
        try {
            /*
             * dumb(false) is important: a redirected Gradle/IDE pipe is not a
             * full-screen terminal. Accepting JLine's dumb fallback would print
             * cursor-control bytes into that pipe and could never provide mouse
             * input. The caller can instead make an explicit, safe AUTO-mode
             * decision and use the line-command frontend.
             */
            opened = TerminalBuilder.builder()
                    .name(TERMINAL_NAME)
                    .system(true)
                    .dumb(false)
                    .encoding(StandardCharsets.UTF_8)
                    .stdinEncoding(StandardCharsets.UTF_8)
                    .stdoutEncoding(StandardCharsets.UTF_8)
                    .stderrEncoding(StandardCharsets.UTF_8)
                    .nativeSignals(false)
                    .signalHandler(Terminal.SignalHandler.SIG_IGN)
                    .build();
            opened.enterRawMode();
            return new JLineTerminalBackend(opened, isWindows(System.getProperty("os.name", "")));
        } catch (IOException | IOError | RuntimeException | LinkageError error) {
            if (opened != null) {
                try {
                    opened.close();
                } catch (IOException | IOError | RuntimeException | LinkageError closeError) {
                    error.addSuppressed(closeError);
                }
            }
            TerminalUnavailableException unavailable = new TerminalUnavailableException(UNAVAILABLE_MESSAGE);
            // Keep the actionable message as the primary error while retaining
            // provider diagnostics for debuggers and test reports.
            unavailable.addSuppressed(error);
            throw unavailable;
        }
    }

    static boolean isWindows(String operatingSystemName) {
        return operatingSystemName != null
                && operatingSystemName.toLowerCase(Locale.ROOT).startsWith("windows");
    }

    @Override
    public InputStream input() {
        return terminal.input();
    }

    @Override
    public OutputStream output() {
        return terminal.output();
    }

    @Override
    public Charset encoding() {
        return terminal.inputEncoding();
    }

    @Override
    public TerminalSize size() {
        org.jline.terminal.Size size = terminal.getSize();
        return new TerminalSize(Math.max(1, size.getColumns()), Math.max(1, size.getRows()));
    }

    @Override
    public void setResizeListener(Consumer<TerminalSize> listener) {
        Objects.requireNonNull(listener, "listener");
        terminal.handle(Terminal.Signal.WINCH, ignored -> listener.accept(size()));
    }

    @Override
    public boolean usesNativeMouseInput() {
        return nativeMouseInput;
    }

    @Override
    public void setNativeMouseTracking(boolean enabled) throws IOException {
        Terminal.MouseTracking tracking = enabled
                ? Terminal.MouseTracking.Button
                : Terminal.MouseTracking.Off;
        try {
            if (!terminal.trackMouse(tracking)) {
                throw new IOException("The native terminal backend does not support mouse tracking.");
            }
        } catch (IOError error) {
            throw new IOException("The native terminal backend could not change mouse tracking.", error);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            terminal.close();
        } catch (IOError error) {
            throw new IOException("The native terminal backend could not restore the console.", error);
        }
    }
}
