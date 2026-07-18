package org.zzvsjs.jflac.examples;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.terminal.MouseCaptureMode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class JLineLanternaTerminalTest {
    @Test
    public void windowsMouseTrackingUsesTheNativeBackendWithoutDuplicateEscapeModes() throws Exception {
        FakeBackend backend = new FakeBackend(true);
        JLineLanternaTerminal terminal = new JLineLanternaTerminal(backend);

        terminal.setMouseCaptureMode(MouseCaptureMode.CLICK_RELEASE_DRAG);
        terminal.enterPrivateMode();

        String enteredOutput = backend.outputText();
        assertEquals(List.of(true), backend.mouseTrackingChanges);
        assertFalse(enteredOutput.contains("\033[?1002h"));
        assertFalse(enteredOutput.contains("\033[?1005h"));

        terminal.close();
        terminal.close();

        assertEquals(List.of(true, false), backend.mouseTrackingChanges);
        assertEquals(1, backend.closeCount);
        assertTrue(backend.outputText().contains("\033[?1049h"));
        assertTrue(backend.outputText().contains("\033[?1049l"));
    }

    @Test
    public void posixStyleBackendUsesLanternasPortableMouseEscapeProtocol() throws Exception {
        FakeBackend backend = new FakeBackend(false);
        JLineLanternaTerminal terminal = new JLineLanternaTerminal(backend);

        terminal.setMouseCaptureMode(MouseCaptureMode.CLICK_RELEASE_DRAG);
        terminal.enterPrivateMode();

        assertEquals(List.of(), backend.mouseTrackingChanges);
        assertTrue(backend.outputText().contains("\033[?1002h"));
        assertTrue(backend.outputText().contains("\033[?1005h"));

        terminal.close();

        assertTrue(backend.outputText().contains("\033[?1002l"));
        assertTrue(backend.outputText().contains("\033[?1005l"));
        assertEquals(1, backend.closeCount);
    }

    @Test
    public void sizeAndResizeEventsAreTranslatedWithoutARealConsole() throws Exception {
        FakeBackend backend = new FakeBackend(true);
        JLineLanternaTerminal terminal = new JLineLanternaTerminal(backend);
        AtomicReference<TerminalSize> resized = new AtomicReference<>();
        terminal.addResizeListener((ignored, size) -> resized.set(size));

        assertEquals(new TerminalSize(120, 40), terminal.getTerminalSize());

        backend.fireResize(new TerminalSize(90, 28));

        assertEquals(new TerminalSize(90, 28), resized.get());
        terminal.close();
    }

    @Test
    public void repeatedWindowsButtonDownRecordsBecomeAContinuousDrag() throws Exception {
        byte[] input = mouseInput(
                mouseRecord(0, 4, 2),
                mouseRecord(0, 15, 2),
                mouseRecord(3, 15, 2),
                mouseRecord(0, 8, 3)
        );
        FakeBackend backend = new FakeBackend(true, input);
        JLineLanternaTerminal terminal = new JLineLanternaTerminal(backend);
        terminal.setMouseCaptureMode(MouseCaptureMode.CLICK_RELEASE_DRAG);

        assertMouseAction(terminal.readInput(), MouseActionType.CLICK_DOWN, 1, 4, 2);
        assertMouseAction(terminal.readInput(), MouseActionType.DRAG, 1, 15, 2);
        assertMouseAction(terminal.readInput(), MouseActionType.CLICK_RELEASE, 0, 15, 2);
        assertMouseAction(terminal.readInput(), MouseActionType.CLICK_DOWN, 1, 8, 3);

        terminal.close();
    }

    @Test
    public void windowsDetectionIsCaseInsensitiveAndDoesNotMisclassifyOtherSystems() {
        assertTrue(JLineTerminalBackend.isWindows("Windows 11"));
        assertTrue(JLineTerminalBackend.isWindows("WINDOWS Server 2025"));
        assertFalse(JLineTerminalBackend.isWindows("Linux"));
        assertFalse(JLineTerminalBackend.isWindows(null));
    }

    private static byte[] mouseRecord(int code, int column, int row) {
        return new byte[] {
                0x1b,
                '[',
                'M',
                (byte) (' ' + code),
                (byte) ('!' + column),
                (byte) ('!' + row)
        };
    }

    private static byte[] mouseInput(byte[]... records) {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        for (byte[] record : records) {
            input.writeBytes(record);
        }
        return input.toByteArray();
    }

    private static void assertMouseAction(
            Object keyStroke,
            MouseActionType expectedType,
            int expectedButton,
            int expectedColumn,
            int expectedRow
    ) {
        MouseAction action = (MouseAction) keyStroke;
        assertEquals(expectedType, action.getActionType());
        assertEquals(expectedButton, action.getButton());
        assertEquals(expectedColumn, action.getPosition().getColumn());
        assertEquals(expectedRow, action.getPosition().getRow());
    }

    private static final class FakeBackend implements PlaybackTerminalBackend {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final boolean nativeMouseInput;
        private final List<Boolean> mouseTrackingChanges = new ArrayList<>();
        private TerminalSize size = new TerminalSize(120, 40);
        private Consumer<TerminalSize> resizeListener = ignored -> { };
        private int closeCount;

        private FakeBackend(boolean nativeMouseInput) {
            this(nativeMouseInput, new byte[0]);
        }

        private FakeBackend(boolean nativeMouseInput, byte[] input) {
            this.nativeMouseInput = nativeMouseInput;
            this.input = new ByteArrayInputStream(input);
        }

        @Override
        public InputStream input() {
            return input;
        }

        @Override
        public OutputStream output() {
            return output;
        }

        @Override
        public Charset encoding() {
            return StandardCharsets.UTF_8;
        }

        @Override
        public TerminalSize size() {
            return size;
        }

        @Override
        public void setResizeListener(Consumer<TerminalSize> listener) {
            resizeListener = listener;
        }

        @Override
        public boolean usesNativeMouseInput() {
            return nativeMouseInput;
        }

        @Override
        public void setNativeMouseTracking(boolean enabled) {
            mouseTrackingChanges.add(enabled);
        }

        @Override
        public void close() throws IOException {
            closeCount++;
        }

        private void fireResize(TerminalSize newSize) {
            size = newSize;
            resizeListener.accept(newSize);
        }

        private String outputText() {
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
