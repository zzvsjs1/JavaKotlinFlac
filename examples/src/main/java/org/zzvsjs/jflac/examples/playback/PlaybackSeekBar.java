package org.zzvsjs.jflac.examples.playback;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.ThemeDefinition;
import com.googlecode.lanterna.gui2.AbstractInteractableComponent;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.InteractableRenderer;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

import java.util.Objects;
import java.util.function.LongConsumer;

/** Keyboard- and mouse-operable timeline that commits a drag only on release. */
final class PlaybackSeekBar extends AbstractInteractableComponent<PlaybackSeekBar> {
    static final long ARROW_STEP_MICROS = 5_000_000L;
    static final long PAGE_STEP_MICROS = 30_000_000L;

    private final LongConsumer seekAction;

    private long positionMicros;
    private long durationMicros = -1L;
    private long previewMicros = -1L;
    private boolean dragging;

    PlaybackSeekBar(LongConsumer seekAction) {
        this.seekAction = Objects.requireNonNull(seekAction, "seekAction");
        setPreferredSize(new TerminalSize(60, 1));
        setEnabled(false);
    }

    void setTimeline(long positionMicros, long durationMicros, boolean seekEnabled) {
        this.durationMicros = durationMicros;
        this.positionMicros = PlaybackTimeline.clampPosition(positionMicros, durationMicros);
        if (!seekEnabled || durationMicros <= 0L) {
            dragging = false;
            previewMicros = -1L;
        }

        setEnabled(seekEnabled && durationMicros > 0L);
        invalidate();
    }

    long displayedPositionMicros() {
        return dragging && previewMicros >= 0L ? previewMicros : positionMicros;
    }

    boolean isDragging() {
        return dragging;
    }

    void cancelDrag() {
        if (!dragging) {
            return;
        }

        dragging = false;
        previewMicros = -1L;
        invalidate();
    }

    void finishDragAtGlobal(TerminalPosition globalPosition) {
        if (!dragging || globalPosition == null) {
            return;
        }

        Integer localColumn = localColumn(globalPosition);
        if (localColumn != null) {
            finishDragAtColumn(localColumn);
        }
    }

    void beginDragAtColumn(int column) {
        if (!isEnabled() || durationMicros <= 0L) {
            return;
        }

        dragging = true;
        previewMicros = timeAtComponentColumn(column);
        invalidate();
    }

    void updateDragAtColumn(int column) {
        if (!dragging) {
            return;
        }

        previewMicros = timeAtComponentColumn(column);
        invalidate();
    }

    void finishDragAtColumn(int column) {
        if (!dragging) {
            return;
        }

        previewMicros = timeAtComponentColumn(column);
        long committedPosition = previewMicros;
        dragging = false;
        previewMicros = -1L;
        positionMicros = committedPosition;
        invalidate();
        seekAction.accept(committedPosition);
    }

    @Override
    protected Result handleKeyStroke(KeyStroke keyStroke) {
        if (!isEnabled()) {
            return super.handleKeyStroke(keyStroke);
        }

        KeyType keyType = keyStroke.getKeyType();
        if (keyType == KeyType.ArrowLeft) {
            return commitKeyboardDelta(-ARROW_STEP_MICROS);
        }

        if (keyType == KeyType.ArrowRight) {
            return commitKeyboardDelta(ARROW_STEP_MICROS);
        }

        if (keyType == KeyType.PageUp) {
            return commitKeyboardDelta(-PAGE_STEP_MICROS);
        }

        if (keyType == KeyType.PageDown) {
            return commitKeyboardDelta(PAGE_STEP_MICROS);
        }

        if (keyType == KeyType.Home) {
            return commitKeyboardPosition(0L);
        }

        if (keyType == KeyType.End) {
            return commitKeyboardPosition(durationMicros);
        }

        if (keyStroke instanceof MouseAction mouseAction) {
            return handleMouseAction(mouseAction);
        }

        return super.handleKeyStroke(keyStroke);
    }

    private Result handleMouseAction(MouseAction mouseAction) {
        Integer column = localColumn(mouseAction.getPosition());
        if (column == null) {
            return Result.UNHANDLED;
        }

        MouseActionType actionType = mouseAction.getActionType();
        if (actionType == MouseActionType.CLICK_DOWN && mouseAction.getButton() == 1) {
            takeFocus();
            beginDragAtColumn(column);
            return Result.HANDLED;
        }

        if (actionType == MouseActionType.DRAG && dragging) {
            updateDragAtColumn(column);
            return Result.HANDLED;
        }

        if (actionType == MouseActionType.CLICK_RELEASE && dragging) {
            finishDragAtColumn(column);
            return Result.HANDLED;
        }

        return Result.UNHANDLED;
    }

    @Override
    protected void afterLeaveFocus(
            Interactable.FocusChangeDirection direction,
            Interactable nextInFocus
    ) {
        cancelDrag();
    }

    private Result commitKeyboardDelta(long deltaMicros) {
        return commitKeyboardPosition(PlaybackTimeline.addClamped(
                displayedPositionMicros(),
                deltaMicros,
                durationMicros
        ));
    }

    private Result commitKeyboardPosition(long targetMicros) {
        long target = PlaybackTimeline.clampPosition(targetMicros, durationMicros);
        dragging = false;
        previewMicros = -1L;
        positionMicros = target;
        invalidate();
        seekAction.accept(target);
        return Result.HANDLED;
    }

    private Integer localColumn(TerminalPosition globalPosition) {
        TerminalPosition origin = toGlobal(TerminalPosition.TOP_LEFT_CORNER);
        if (origin == null) {
            return null;
        }

        return globalPosition.getColumn() - origin.getColumn();
    }

    private long timeAtComponentColumn(int componentColumn) {
        int width = Math.max(1, getSize().getColumns());
        int innerWidth = Math.max(1, width - 2);
        int innerColumn = componentColumn - 1;
        return PlaybackTimeline.timeAtColumn(innerColumn, innerWidth, durationMicros);
    }

    @Override
    protected InteractableRenderer<PlaybackSeekBar> createDefaultRenderer() {
        return new InteractableRenderer<>() {
            @Override
            public TerminalPosition getCursorLocation(PlaybackSeekBar component) {
                return null;
            }

            @Override
            public TerminalSize getPreferredSize(PlaybackSeekBar component) {
                return new TerminalSize(60, 1);
            }

            @Override
            public void drawComponent(TextGUIGraphics graphics, PlaybackSeekBar component) {
                drawTimeline(graphics, component);
            }
        };
    }

    private static void drawTimeline(TextGUIGraphics graphics, PlaybackSeekBar component) {
        ThemeDefinition theme = component.getThemeDefinition();
        if (!component.isEnabled()) {
            graphics.applyThemeStyle(theme.getInsensitive());
        } else if (component.isFocused()) {
            graphics.applyThemeStyle(theme.getActive());
        } else {
            graphics.applyThemeStyle(theme.getNormal());
        }

        int width = graphics.getSize().getColumns();
        if (width <= 0) {
            return;
        }

        graphics.fill(' ');
        if (width == 1) {
            graphics.setCharacter(0, 0, '|');
            return;
        }

        graphics.setCharacter(0, 0, '[');
        graphics.setCharacter(width - 1, 0, ']');
        int innerWidth = width - 2;
        if (innerWidth <= 0) {
            return;
        }

        if (component.durationMicros <= 0L) {
            for (int column = 0; column < innerWidth; column++) {
                graphics.setCharacter(column + 1, 0, '-');
            }

            return;
        }

        int marker = PlaybackTimeline.columnAtTime(
                component.displayedPositionMicros(),
                innerWidth,
                component.durationMicros
        );
        for (int column = 0; column < innerWidth; column++) {
            char character = column < marker ? '=' : '-';
            graphics.setCharacter(column + 1, 0, character);
        }

        graphics.setCharacter(marker + 1, 0, '|');
    }
}
