package org.zzvsjs.jflac.examples;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class PlaybackTimelineTest {
    @Test
    public void timeAndColumnMappingsIncludeBothEndpoints() {
        assertEquals(0L, PlaybackTimeline.timeAtColumn(0, 11, 100_000_000L));
        assertEquals(50_000_000L, PlaybackTimeline.timeAtColumn(5, 11, 100_000_000L));
        assertEquals(100_000_000L, PlaybackTimeline.timeAtColumn(10, 11, 100_000_000L));

        assertEquals(0, PlaybackTimeline.columnAtTime(0L, 11, 100_000_000L));
        assertEquals(5, PlaybackTimeline.columnAtTime(50_000_000L, 11, 100_000_000L));
        assertEquals(10, PlaybackTimeline.columnAtTime(100_000_000L, 11, 100_000_000L));
    }

    @Test
    public void mappingClampsOutOfRangePositionsAndColumns() {
        assertEquals(0L, PlaybackTimeline.timeAtColumn(-50, 11, 100L));
        assertEquals(100L, PlaybackTimeline.timeAtColumn(50, 11, 100L));
        assertEquals(0, PlaybackTimeline.columnAtTime(-1L, 11, 100L));
        assertEquals(10, PlaybackTimeline.columnAtTime(101L, 11, 100L));
    }

    @Test
    public void mappingHandlesUnknownDurationsAndNarrowBars() {
        assertEquals(0L, PlaybackTimeline.timeAtColumn(2, 5, -1L));
        assertEquals(0L, PlaybackTimeline.timeAtColumn(2, 1, 100L));
        assertEquals(0, PlaybackTimeline.columnAtTime(50L, 1, 100L));
    }

    @Test
    public void mappingDoesNotOverflowForLongDurations() {
        assertEquals(
                Long.MAX_VALUE,
                PlaybackTimeline.timeAtColumn(Integer.MAX_VALUE - 1, Integer.MAX_VALUE, Long.MAX_VALUE)
        );
        assertEquals(
                Integer.MAX_VALUE - 1,
                PlaybackTimeline.columnAtTime(Long.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE)
        );
    }

    @Test
    public void relativeMovementClampsAndSurvivesOverflow() {
        assertEquals(0L, PlaybackTimeline.addClamped(5L, -10L, 100L));
        assertEquals(100L, PlaybackTimeline.addClamped(95L, 10L, 100L));
        assertEquals(Long.MAX_VALUE, PlaybackTimeline.addClamped(Long.MAX_VALUE - 1L, 10L, -1L));
    }
}

