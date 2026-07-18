package org.zzvsjs.jflac.examples;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.AbstractListBox;
import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.Container;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.gui2.dialogs.FileDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** File dialog that keeps the chosen filename visibly selected after a click. */
final class PlaybackFileDialog extends FileDialog {
    private static final int MAXIMUM_LIST_COLUMNS = 72;
    private static final int MAXIMUM_LIST_ROWS = 20;
    private static final int MINIMUM_LIST_COLUMNS = 12;
    private static final int MINIMUM_LIST_ROWS = 3;

    /*
     * Lanterna adds two list borders, the window border, and the controls
     * surrounding the lists outside the requested list dimensions.
     */
    private static final int HORIZONTAL_CHROME_COLUMNS = 6;
    private static final int VERTICAL_CHROME_ROWS = 9;

    private final ActionListBox fileListBox;
    private final TextBox fileNameBox;

    PlaybackFileDialog(File selectedObject, TerminalSize terminalSize) {
        super(
                "Open FLAC or Ogg FLAC",
                "Select a .flac, .oga, or .ogg music file",
                "Open",
                suggestedSizeFor(terminalSize),
                false,
                selectedObject
        );

        DialogControls controls = locateDialogControls(getComponent());
        fileListBox = controls.fileListBox();
        fileNameBox = controls.fileNameBox();
        fileListBox.setListItemRenderer(new SelectedFileRenderer(fileNameBox));
    }

    static TerminalSize suggestedSizeFor(TerminalSize terminalSize) {
        Objects.requireNonNull(terminalSize, "terminalSize");

        /*
         * FileDialog treats the suggested height as the list height, rather
         * than the complete dialog height. Reserving its surrounding rows
         * keeps the filename field and Open/Cancel buttons on screen.
         */
        int columns = Math.max(
                MINIMUM_LIST_COLUMNS,
                Math.min(MAXIMUM_LIST_COLUMNS, terminalSize.getColumns() - HORIZONTAL_CHROME_COLUMNS)
        );
        int rows = Math.max(
                MINIMUM_LIST_ROWS,
                Math.min(MAXIMUM_LIST_ROWS, terminalSize.getRows() - VERTICAL_CHROME_ROWS)
        );
        return new TerminalSize(columns, rows);
    }

    ActionListBox fileListBox() {
        return fileListBox;
    }

    TextBox fileNameBox() {
        return fileNameBox;
    }

    private static DialogControls locateDialogControls(Component root) {
        List<ActionListBox> listBoxes = new ArrayList<>();
        List<TextBox> textBoxes = new ArrayList<>();
        collectControls(root, listBoxes, textBoxes);

        if (listBoxes.size() != 2 || textBoxes.size() != 1) {
            throw new IllegalStateException(
                    "Lanterna FileDialog controls changed; expected two lists and one filename field."
            );
        }

        /*
         * Lanterna's file list occupies two thirds of the dialog width, while
         * its directory list occupies one third. Selecting the wider list is
         * less fragile than depending on the component traversal order.
         */
        ActionListBox widestList = listBoxes.stream()
                .max(Comparator.comparingInt(list -> list.getPreferredSize().getColumns()))
                .orElseThrow();
        int narrowestWidth = listBoxes.stream()
                .mapToInt(list -> list.getPreferredSize().getColumns())
                .min()
                .orElseThrow();
        if (widestList.getPreferredSize().getColumns() == narrowestWidth) {
            throw new IllegalStateException(
                    "Lanterna FileDialog controls changed; the file list is no longer wider than the directory list."
            );
        }
        return new DialogControls(widestList, textBoxes.getFirst());
    }

    private static void collectControls(
            Component component,
            List<ActionListBox> listBoxes,
            List<TextBox> textBoxes
    ) {
        if (component instanceof ActionListBox listBox) {
            listBoxes.add(listBox);
        }
        if (component instanceof TextBox textBox) {
            textBoxes.add(textBox);
        }
        if (component instanceof Container container) {
            for (Component child : container.getChildren()) {
                collectControls(child, listBoxes, textBoxes);
            }
        }
    }

    private record DialogControls(ActionListBox fileListBox, TextBox fileNameBox) {
    }

    private static final class SelectedFileRenderer
            extends AbstractListBox.ListItemRenderer<Runnable, ActionListBox> {
        private final TextBox fileNameBox;

        private SelectedFileRenderer(TextBox fileNameBox) {
            this.fileNameBox = fileNameBox;
        }

        @Override
        public void drawItem(
                TextGUIGraphics graphics,
                ActionListBox listBox,
                int index,
                Runnable item,
                boolean selected,
                boolean focused
        ) {
            /*
             * Stock FileDialog moves focus to Open immediately after choosing
             * a file. Its default renderer then paints the row as ordinary
             * text. When this list is unfocused, use the filename field as the
             * durable selection and retain the normal selected-row style.
             */
            boolean chosenFile = !focused && fileNameBox.getText().equals(item.toString());
            super.drawItem(
                    graphics,
                    listBox,
                    index,
                    item,
                    selected || chosenFile,
                    focused || chosenFile
            );
        }
    }
}
