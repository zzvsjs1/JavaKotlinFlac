package org.zzvsjs.jflac.examples;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class PlaybackSeekBarTest {
    @Test
    public void mouseDragPreviewsWithoutSeekingAndCommitsOnceOnRelease() {
        List<Long> seeks = new ArrayList<>();
        PlaybackSeekBar seekBar = new PlaybackSeekBar(seeks::add);
        seekBar.setSize(new TerminalSize(12, 1));
        seekBar.setTimeline(25_000_000L, 100_000_000L, true);
        TerminalPosition origin = attachToWindow(seekBar);

        seekBar.handleKeyStroke(new MouseAction(
                MouseActionType.CLICK_DOWN,
                1,
                origin.withRelativeColumn(1)
        ));
        seekBar.handleKeyStroke(new MouseAction(
                MouseActionType.DRAG,
                1,
                origin.withRelativeColumn(10)
        ));

        assertTrue(seekBar.isDragging());
        assertEquals(100_000_000L, seekBar.displayedPositionMicros());
        assertEquals(List.of(), seeks);

        seekBar.handleKeyStroke(new MouseAction(
                MouseActionType.CLICK_RELEASE,
                0,
                origin.withRelativeColumn(100)
        ));

        assertFalse(seekBar.isDragging());
        assertEquals(List.of(100_000_000L), seeks);
    }

    @Test
    public void focusedKeyboardControlsUseSmallLargeAndEndpointSteps() {
        List<Long> seeks = new ArrayList<>();
        PlaybackSeekBar seekBar = new PlaybackSeekBar(seeks::add);
        seekBar.setSize(new TerminalSize(62, 1));
        seekBar.setTimeline(0L, 120_000_000L, true);

        seekBar.handleKeyStroke(new KeyStroke(KeyType.ArrowRight));
        seekBar.handleKeyStroke(new KeyStroke(KeyType.PageDown));
        seekBar.handleKeyStroke(new KeyStroke(KeyType.End));
        seekBar.handleKeyStroke(new KeyStroke(KeyType.Home));

        assertEquals(List.of(5_000_000L, 35_000_000L, 120_000_000L, 0L), seeks);
    }

    @Test
    public void unknownDurationDisablesPointerSeeking() {
        List<Long> seeks = new ArrayList<>();
        PlaybackSeekBar seekBar = new PlaybackSeekBar(seeks::add);
        seekBar.setSize(new TerminalSize(20, 1));
        seekBar.setTimeline(10_000_000L, -1L, false);

        seekBar.beginDragAtColumn(10);

        assertFalse(seekBar.isEnabled());
        assertFalse(seekBar.isDragging());
        assertEquals(List.of(), seeks);
    }

    @Test
    public void nonLeftMouseButtonDoesNotStartDragging() {
        List<Long> seeks = new ArrayList<>();
        PlaybackSeekBar seekBar = new PlaybackSeekBar(seeks::add);
        seekBar.setSize(new TerminalSize(20, 1));
        seekBar.setTimeline(0L, 60_000_000L, true);
        TerminalPosition origin = attachToWindow(seekBar);

        seekBar.handleKeyStroke(new MouseAction(
                MouseActionType.CLICK_DOWN,
                3,
                origin.withRelativeColumn(10)
        ));

        assertFalse(seekBar.isDragging());
        assertEquals(List.of(), seeks);
    }

    @Test
    public void cancellingALostReleaseRestoresLivePlaybackPosition() {
        List<Long> seeks = new ArrayList<>();
        PlaybackSeekBar seekBar = new PlaybackSeekBar(seeks::add);
        seekBar.setSize(new TerminalSize(20, 1));
        seekBar.setTimeline(10_000_000L, 60_000_000L, true);

        seekBar.beginDragAtColumn(18);
        seekBar.cancelDrag();

        assertFalse(seekBar.isDragging());
        assertEquals(10_000_000L, seekBar.displayedPositionMicros());
        assertEquals(List.of(), seeks);
    }

    private static TerminalPosition attachToWindow(PlaybackSeekBar seekBar) {
        Panel panel = new Panel(new GridLayout(1));
        panel.addComponent(seekBar);
        panel.setPosition(new TerminalPosition(2, 1));

        BasicWindow window = new BasicWindow();
        window.setPosition(new TerminalPosition(10, 5));
        window.setContentOffset(TerminalPosition.TOP_LEFT_CORNER);
        window.setComponent(panel);
        seekBar.setPosition(new TerminalPosition(3, 2));
        return seekBar.toGlobal(TerminalPosition.TOP_LEFT_CORNER);
    }
}
