package org.zzvsjs.jflac.examples.playback;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.graphics.ThemeStyle;
import com.googlecode.lanterna.gui2.AbstractListBox;
import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.Container;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class PlaybackFileDialogTest {
    @Test
    public void mouseClickLeavesAVisibleSelectionOnTheClickedFile(@TempDir Path temporaryDirectory)
            throws Exception {
        Files.writeString(temporaryDirectory.resolve("alpha.flac"), "");
        Files.writeString(temporaryDirectory.resolve("bravo.flac"), "");

        PlaybackFileDialog dialog = new PlaybackFileDialog(
                temporaryDirectory.toFile(),
                new TerminalSize(100, 30)
        );
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(100, 30));
        try (TerminalScreen screen = new TerminalScreen(terminal)) {
            screen.startScreen();
            MultiWindowTextGUI textGUI = new MultiWindowTextGUI(screen);
            textGUI.addWindow(dialog);
            textGUI.updateScreen();

            ActionListBox fileList = dialog.fileListBox();
            assertSame(fileList, dialog.getFocusedInteractable());
            int selectedIndex = indexOf(fileList, "bravo.flac");
            TerminalPosition selectedPosition = fileList.getGlobalPosition()
                    .withRelativeRow(selectedIndex);

            assertTrue(dialog.handleInput(new MouseAction(
                    MouseActionType.CLICK_DOWN,
                    1,
                    selectedPosition
            )));
            assertTrue(dialog.handleInput(new MouseAction(
                    MouseActionType.CLICK_RELEASE,
                    0,
                    selectedPosition
            )));

            assertEquals(selectedIndex, fileList.getSelectedIndex());
            assertEquals("bravo.flac", dialog.fileNameBox().getText());
            assertNotSame(fileList, dialog.getFocusedInteractable());

            textGUI.updateScreen();
            ThemeStyle selectedStyle = fileList.getTheme()
                    .getDefinition(AbstractListBox.class)
                    .getSelected();
            assertStyleEquals(selectedStyle, screen.getBackCharacter(selectedPosition));

            TerminalPosition previousPosition = fileList.getGlobalPosition();
            ThemeStyle normalStyle = fileList.getTheme()
                    .getDefinition(AbstractListBox.class)
                    .getNormal();
            assertStyleEquals(normalStyle, screen.getBackCharacter(previousPosition));
        }
    }

    @Test
    public void keyboardEnterStillMovesFromTheFileListToOpen(@TempDir Path temporaryDirectory)
            throws Exception {
        Files.writeString(temporaryDirectory.resolve("music.flac"), "");

        PlaybackFileDialog dialog = new PlaybackFileDialog(
                temporaryDirectory.toFile(),
                new TerminalSize(100, 30)
        );
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(100, 30));
        try (TerminalScreen screen = new TerminalScreen(terminal)) {
            screen.startScreen();
            MultiWindowTextGUI textGUI = new MultiWindowTextGUI(screen);
            textGUI.addWindow(dialog);
            textGUI.updateScreen();

            ActionListBox fileList = dialog.fileListBox();
            assertSame(fileList, dialog.getFocusedInteractable());
            assertTrue(dialog.handleInput(new KeyStroke(KeyType.Enter)));

            assertEquals("music.flac", dialog.fileNameBox().getText());
            assertNotSame(fileList, dialog.getFocusedInteractable());
        }
    }

    @Test
    public void suggestedSizeReservesSpaceForDialogControls() {
        assertEquals(
                new TerminalSize(72, 15),
                PlaybackFileDialog.suggestedSizeFor(new TerminalSize(94, 24))
        );
        assertEquals(
                new TerminalSize(34, 11),
                PlaybackFileDialog.suggestedSizeFor(new TerminalSize(40, 20))
        );
        assertEquals(
                new TerminalSize(72, 20),
                PlaybackFileDialog.suggestedSizeFor(new TerminalSize(120, 40))
        );
    }

    @Test
    public void compactTerminalKeepsDialogButtonsOnScreen(@TempDir Path temporaryDirectory)
            throws Exception {
        TerminalSize terminalSize = new TerminalSize(94, 24);
        PlaybackFileDialog dialog = new PlaybackFileDialog(temporaryDirectory.toFile(), terminalSize);
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(terminalSize);
        try (TerminalScreen screen = new TerminalScreen(terminal)) {
            screen.startScreen();
            MultiWindowTextGUI textGUI = new MultiWindowTextGUI(screen);
            textGUI.addWindow(dialog);
            textGUI.updateScreen();

            List<Button> buttons = new ArrayList<>();
            collectComponents(dialog.getComponent(), Button.class, buttons);
            assertEquals(2, buttons.size());
            for (Button button : buttons) {
                int bottomRow = button.getGlobalPosition().getRow() + button.getSize().getRows();
                assertTrue(bottomRow <= terminalSize.getRows());
            }
        }
    }

    private static int indexOf(ActionListBox listBox, String label) {
        for (int index = 0; index < listBox.getItemCount(); index++) {
            if (label.equals(listBox.getItemAt(index).toString())) {
                return index;
            }
        }

        throw new AssertionError("Missing list item: " + label);
    }

    private static void assertStyleEquals(ThemeStyle expected, TextCharacter actual) {
        assertEquals(expected.getForeground(), actual.getForegroundColor());
        assertEquals(expected.getBackground(), actual.getBackgroundColor());
        assertEquals(expected.getSGRs(), actual.getModifiers());
    }

    private static <T extends Component> void collectComponents(
            Component component,
            Class<T> type,
            List<T> matches
    ) {
        if (type.isInstance(component)) {
            matches.add(type.cast(component));
        }

        if (component instanceof Container container) {
            for (Component child : container.getChildren()) {
                collectComponents(child, type, matches);
            }
        }
    }
}
