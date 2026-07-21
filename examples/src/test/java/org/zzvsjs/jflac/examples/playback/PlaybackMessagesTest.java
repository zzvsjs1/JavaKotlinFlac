package org.zzvsjs.jflac.examples.playback;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class PlaybackMessagesTest {
    @Test
    public void deepestCauseProvidesTheUsefulMessage() {
        IllegalStateException root = new IllegalStateException("mixer disconnected");
        RuntimeException wrapper = new RuntimeException("playback failed", root);

        assertEquals(
                "mixer disconnected",
                PlaybackMessages.rootCauseMessage(wrapper)
        );
    }

    @Test
    public void malformedCauseCycleCannotLoopForever() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second");
        first.initCause(second);
        second.initCause(first);

        assertEquals("second", PlaybackMessages.rootCauseMessage(first));
    }
}
