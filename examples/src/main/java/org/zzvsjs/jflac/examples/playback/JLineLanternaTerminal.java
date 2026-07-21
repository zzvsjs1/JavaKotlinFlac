package org.zzvsjs.jflac.examples.playback;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.terminal.MouseCaptureMode;
import com.googlecode.lanterna.terminal.ansi.ANSITerminal;

import java.io.IOError;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adapts JLine's native system-terminal lifecycle to Lanterna's ANSI renderer.
 *
 * <p>JLine owns raw mode, Win32/POSIX console access, resizing, and restoration.
 * Lanterna continues to own the widgets and ANSI screen drawing. On Windows,
 * JLine converts native key and mouse input records to the X10-style sequences
 * already understood by Lanterna's input decoder.</p>
 */
final class JLineLanternaTerminal extends ANSITerminal {
    private final PlaybackTerminalBackend backend;
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean nativeMouseTracking;
    private int nativePressedMouseButton;
    private TerminalPosition nativeMousePosition;

    JLineLanternaTerminal(PlaybackTerminalBackend backend) {
        super(backend.input(), backend.output(), backend.encoding());
        this.backend = Objects.requireNonNull(backend, "backend");
        backend.setResizeListener(this::onResized);
    }

    static JLineLanternaTerminal open() throws IOException {
        PlaybackTerminalBackend backend = JLineTerminalBackend.open();
        try {
            return new JLineLanternaTerminal(backend);
        } catch (IOError | RuntimeException | LinkageError error) {
            try {
                backend.close();
            } catch (IOException | IOError | RuntimeException | LinkageError closeError) {
                if (closeError != error) {
                    error.addSuppressed(closeError);
                }
            }

            throw error;
        }
    }

    @Override
    protected TerminalSize findTerminalSize() {
        return backend.size();
    }

    @Override
    public void setMouseCaptureMode(MouseCaptureMode mouseCaptureMode) throws IOException {
        if (backend.usesNativeMouseInput()) {
            /*
             * Win32 delivers MOUSE_EVENT_RECORD values only after
             * ENABLE_MOUSE_INPUT is set. JLine performs that native mode
             * transition and writes X10 events into its input pipe. Calling
             * ANSITerminal's escape-only implementation here would not enable
             * those records and is therefore intentionally avoided.
             */
            boolean enabled = mouseCaptureMode != null;
            backend.setNativeMouseTracking(enabled);
            nativeMouseTracking = enabled;
            nativePressedMouseButton = 0;
            nativeMousePosition = null;
            return;
        }

        super.setMouseCaptureMode(mouseCaptureMode);
    }

    @Override
    public KeyStroke readInput() throws IOException {
        return normaliseNativeMouseEvent(super.readInput());
    }

    @Override
    public KeyStroke pollInput() throws IOException {
        return normaliseNativeMouseEvent(super.pollInput());
    }

    private KeyStroke normaliseNativeMouseEvent(KeyStroke keyStroke) {
        if (!backend.usesNativeMouseInput() || !(keyStroke instanceof MouseAction mouseAction)) {
            return keyStroke;
        }

        switch (mouseAction.getActionType()) {
            case CLICK_DOWN -> {
                if (nativePressedMouseButton == mouseAction.getButton()
                        && nativeMousePosition != null
                        && !nativeMousePosition.equals(mouseAction.getPosition())) {
                    /*
                     * JLine's Windows JNI provider currently emits another
                     * X10 button-down record for MOUSE_MOVED while a button is
                     * held. Lanterna requires the X10 motion bit and otherwise
                     * treats every point as a fresh click. Treating a repeated
                     * down at a new coordinate as motion preserves the first
                     * click and gives widgets a portable DRAG event stream.
                     */
                    MouseAction drag = new MouseAction(
                            MouseActionType.DRAG,
                            mouseAction.getButton(),
                            mouseAction.getPosition()
                    );
                    nativeMousePosition = mouseAction.getPosition();
                    return drag;
                }

                nativePressedMouseButton = mouseAction.getButton();
                nativeMousePosition = mouseAction.getPosition();
            }

            case CLICK_RELEASE -> {
                nativePressedMouseButton = 0;
                nativeMousePosition = mouseAction.getPosition();
            }

            case DRAG -> {
                nativePressedMouseButton = mouseAction.getButton();
                nativeMousePosition = mouseAction.getPosition();
            }

            default -> {
                // Move and wheel events do not change the pressed button.
            }
        }

        return mouseAction;
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        Throwable failure = null;
        if (nativeMouseTracking) {
            try {
                backend.setNativeMouseTracking(false);
            } catch (IOException | IOError | RuntimeException | LinkageError error) {
                failure = error;
            } finally {
                nativeMouseTracking = false;
                nativePressedMouseButton = 0;
                nativeMousePosition = null;
            }
        }

        try {
            super.close();
        } catch (IOException | IOError | RuntimeException | LinkageError error) {
            failure = merge(failure, error);
        }

        try {
            backend.close();
        } catch (IOException | IOError | RuntimeException | LinkageError error) {
            failure = merge(failure, error);
        }

        rethrowCloseFailure(failure);
    }

    private static Throwable merge(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }

        if (first != next) {
            first.addSuppressed(next);
        }

        return first;
    }

    private static void rethrowCloseFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException ioFailure) {
            throw ioFailure;
        }

        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }

        if (failure instanceof Error errorFailure) {
            throw errorFailure;
        }

        if (failure != null) {
            throw new IOException("The terminal could not be restored.", failure);
        }
    }
}
