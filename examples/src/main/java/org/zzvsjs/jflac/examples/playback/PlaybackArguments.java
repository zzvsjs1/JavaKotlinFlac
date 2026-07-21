package org.zzvsjs.jflac.examples.playback;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Parsed command-line configuration for the terminal playback demo.
 *
 * <p>The parser is kept separate from audio-device work so argument and time
 * behaviour can be tested on machines without a mixer.</p>
 */
record PlaybackArguments(
        Path input,
        long startMicros,
        boolean startPaused,
        Mode mode,
        boolean help
) {
    enum Mode {
        AUTO,
        TUI,
        CONSOLE,
        HEADLESS
    }

    private static final BigDecimal MICROS_PER_SECOND = BigDecimal.valueOf(1_000_000L);
    private static final BigDecimal SECONDS_PER_MINUTE = BigDecimal.valueOf(60L);
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3_600L);

    static PlaybackArguments parse(String[] args) {
        Objects.requireNonNull(args, "args");

        Path input = null;
        long startMicros = 0L;
        boolean startPaused = false;
        Mode mode = Mode.AUTO;
        boolean help = false;
        boolean positionalOnly = false;

        for (int index = 0; index < args.length; index++) {
            String argument = Objects.requireNonNull(args[index], "Command-line arguments must not contain null.");

            if (positionalOnly) {
                input = assignInput(input, argument);
                continue;
            }

            if ("--".equals(argument)) {
                positionalOnly = true;
            } else if ("-h".equals(argument) || "--help".equals(argument)) {
                help = true;
            } else if ("--paused".equals(argument)) {
                startPaused = true;
            } else if ("--tui".equals(argument)) {
                if (mode != Mode.AUTO && mode != Mode.TUI) {
                    throw new IllegalArgumentException(
                            "--tui cannot be combined with --console or --no-controls."
                    );
                }

                mode = Mode.TUI;
            } else if ("--console".equals(argument)) {
                if (mode == Mode.HEADLESS) {
                    throw new IllegalArgumentException("--console cannot be combined with --no-controls.");
                }

                if (mode == Mode.TUI) {
                    throw new IllegalArgumentException("--console cannot be combined with --tui.");
                }

                mode = Mode.CONSOLE;
            } else if ("--no-controls".equals(argument)) {
                if (mode == Mode.CONSOLE) {
                    throw new IllegalArgumentException("--console cannot be combined with --no-controls.");
                }

                if (mode == Mode.TUI) {
                    throw new IllegalArgumentException("--no-controls cannot be combined with --tui.");
                }

                mode = Mode.HEADLESS;
            } else if ("-f".equals(argument) || "--file".equals(argument)) {
                input = assignInput(input, requireOptionValue(args, ++index, argument));
            } else if (argument.startsWith("--file=")) {
                input = assignInput(input, argument.substring("--file=".length()));
            } else if ("-s".equals(argument) || "--start".equals(argument)) {
                startMicros = parseTimeMicros(requireOptionValue(args, ++index, argument));
            } else if (argument.startsWith("--start=")) {
                startMicros = parseTimeMicros(argument.substring("--start=".length()));
            } else if (argument.startsWith("-")) {
                throw new IllegalArgumentException("Unknown playback option: " + argument);
            } else {
                input = assignInput(input, argument);
            }
        }

        if (startPaused && mode == Mode.HEADLESS) {
            throw new IllegalArgumentException("--paused cannot be combined with --no-controls.");
        }

        return new PlaybackArguments(input, startMicros, startPaused, mode, help);
    }

    boolean controlsEnabled() {
        return mode != Mode.HEADLESS;
    }

    PlaybackArguments withInput(Path resolvedInput) {
        return new PlaybackArguments(resolvedInput, startMicros, startPaused, mode, help);
    }

    private static String requireOptionValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option + ".");
        }

        return args[index];
    }

    private static Path assignInput(Path existing, String rawValue) {
        if (existing != null) {
            throw new IllegalArgumentException("Specify only one input music file.");
        }

        String value = stripOuterQuotes(Objects.requireNonNull(rawValue, "Input path must not be null.")).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("The input music file path must not be empty.");
        }

        return Path.of(value).normalize();
    }

    /**
     * Parses seconds, {@code mm:ss}, or {@code hh:mm:ss} into microseconds.
     * Decimal seconds are accepted in the final component. BigDecimal keeps
     * overflow and rounding behaviour deterministic for command-line input.
     */
    static long parseTimeMicros(String rawValue) {
        String value = stripOuterQuotes(Objects.requireNonNull(rawValue, "time")).trim();
        if (value.isEmpty()) {
            throw invalidTime(rawValue);
        }

        String[] components = value.split(":", -1);
        if (components.length < 1 || components.length > 3) {
            throw invalidTime(rawValue);
        }

        try {
            BigDecimal totalSeconds;
            if (components.length == 1) {
                totalSeconds = parseNonNegativeDecimal(components[0], rawValue);
            } else if (components.length == 2) {
                long minutes = parseWholeComponent(components[0], rawValue);
                BigDecimal seconds = parseSecondComponent(components[1], rawValue);
                totalSeconds = BigDecimal.valueOf(minutes).multiply(SECONDS_PER_MINUTE).add(seconds);
            } else {
                long hours = parseWholeComponent(components[0], rawValue);
                long minutes = parseWholeComponent(components[1], rawValue);
                if (minutes >= 60L) {
                    throw invalidTime(rawValue);
                }

                BigDecimal seconds = parseSecondComponent(components[2], rawValue);
                totalSeconds = BigDecimal.valueOf(hours)
                        .multiply(SECONDS_PER_HOUR)
                        .add(BigDecimal.valueOf(minutes).multiply(SECONDS_PER_MINUTE))
                        .add(seconds);
            }

            return totalSeconds
                    .multiply(MICROS_PER_SECOND)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (ArithmeticException error) {
            throw invalidTime(rawValue);
        }
    }

    private static long parseWholeComponent(String component, String original) {
        if (component.isEmpty() || !component.chars().allMatch(Character::isDigit)) {
            throw invalidTime(original);
        }

        try {
            return Long.parseLong(component);
        } catch (NumberFormatException error) {
            throw invalidTime(original);
        }
    }

    private static BigDecimal parseSecondComponent(String component, String original) {
        BigDecimal seconds = parseNonNegativeDecimal(component, original);
        if (seconds.compareTo(SECONDS_PER_MINUTE) >= 0) {
            throw invalidTime(original);
        }

        return seconds;
    }

    private static BigDecimal parseNonNegativeDecimal(String component, String original) {
        try {
            BigDecimal value = new BigDecimal(component);
            if (value.signum() < 0) {
                throw invalidTime(original);
            }

            return value;
        } catch (NumberFormatException error) {
            throw invalidTime(original);
        }
    }

    private static IllegalArgumentException invalidTime(String value) {
        return new IllegalArgumentException(
                "Invalid time '" + value + "'. Use seconds, mm:ss, or hh:mm:ss."
        );
    }

    static String formatTime(long micros) {
        long safeMicros = Math.max(0L, micros);
        long totalSeconds = safeMicros / 1_000_000L;
        long seconds = totalSeconds % 60L;
        long totalMinutes = totalSeconds / 60L;
        long minutes = totalMinutes % 60L;
        long hours = totalMinutes / 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }

        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    static String stripOuterQuotes(String value) {
        if (value != null && value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }

        return value;
    }

    static String usage() {
        return """
                Usage: jflac-playback [options] [input.flac]

                  -f, --file <path>   Music file; a positional path also works
                  -s, --start <time>  Start at seconds, mm:ss, or hh:mm:ss
                      --paused        Load the track without starting playback
                      --tui           Require the native full-screen terminal UI
                      --console       Use the line-command interface
                      --no-controls   Play without reading interactive commands
                  -h, --help          Show this help

                The native terminal UI is preferred automatically. If no real system
                terminal is attached, automatic mode uses the line-command interface.
                """;
    }
}
