package org.zzvsjs.jflac.examples.playback;

import java.math.BigInteger;

/** Overflow-safe position and terminal-column calculations for the seek bar. */
final class PlaybackTimeline {
    private PlaybackTimeline() {
    }

    static long clampPosition(long positionMicros, long durationMicros) {
        long nonNegative = Math.max(0L, positionMicros);
        return durationMicros < 0L ? nonNegative : Math.min(nonNegative, durationMicros);
    }

    static long timeAtColumn(int column, int width, long durationMicros) {
        if (durationMicros <= 0L || width <= 1) {
            return 0L;
        }

        int lastColumn = width - 1;
        int boundedColumn = Math.max(0, Math.min(column, lastColumn));
        return scale(boundedColumn, durationMicros, lastColumn);
    }

    static int columnAtTime(long positionMicros, int width, long durationMicros) {
        if (durationMicros <= 0L || width <= 1) {
            return 0;
        }

        long boundedPosition = clampPosition(positionMicros, durationMicros);
        return Math.toIntExact(scale(boundedPosition, width - 1L, durationMicros));
    }

    static long addClamped(long positionMicros, long deltaMicros, long durationMicros) {
        long current = clampPosition(positionMicros, durationMicros);
        long target;
        try {
            target = Math.addExact(current, deltaMicros);
        } catch (ArithmeticException ignored) {
            target = deltaMicros < 0L ? 0L : Long.MAX_VALUE;
        }

        return clampPosition(target, durationMicros);
    }

    private static long scale(long value, long numerator, long denominator) {
        if (value == 0L || numerator == 0L) {
            return 0L;
        }

        if (value < 0L || numerator < 0L || denominator <= 0L) {
            throw new IllegalArgumentException("Timeline scaling values must be non-negative.");
        }

        // A track duration can be close to Long.MAX_VALUE, so multiplying two
        // longs first would silently overflow. BigInteger keeps endpoint and
        // rounding behaviour exact; this path runs only for UI-sized values.
        return BigInteger.valueOf(value)
                .multiply(BigInteger.valueOf(numerator))
                .divide(BigInteger.valueOf(denominator))
                .longValueExact();
    }
}
