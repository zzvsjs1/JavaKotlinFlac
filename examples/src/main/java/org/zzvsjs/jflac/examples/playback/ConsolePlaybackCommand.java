package org.zzvsjs.jflac.examples.playback;

import java.util.Locale;

/**
 * One parsed line from the interactive playback console.
 */
record ConsolePlaybackCommand(Type type, long timeMicros, String argument) {
    enum Type {
        TOGGLE_PAUSE, PAUSE, RESUME, SEEK_ABSOLUTE, SEEK_RELATIVE, STATUS, OPEN, LIST, HELP, QUIT
    }

    static ConsolePlaybackCommand parse(String rawLine) {
        String line = rawLine == null ? "" : rawLine.trim();
        if (line.isEmpty()) {
            return new ConsolePlaybackCommand(Type.STATUS, 0L, null);
        }

        if ((line.startsWith("+") || line.startsWith("-")) && line.length() > 1) {
            long magnitude = PlaybackArguments.parseTimeMicros(line.substring(1));
            long relative = line.charAt(0) == '-' ? Math.negateExact(magnitude) : magnitude;
            return new ConsolePlaybackCommand(Type.SEEK_RELATIVE, relative, null);
        }

        int separator = firstWhitespace(line);
        String verb = (separator < 0 ? line : line.substring(0, separator)).toLowerCase(Locale.ROOT);
        String remainder = separator < 0 ? "" : line.substring(separator).trim();

        return switch (verb) {
            case "p", "toggle" -> new ConsolePlaybackCommand(Type.TOGGLE_PAUSE, 0L, null);
            case "pause" -> new ConsolePlaybackCommand(Type.PAUSE, 0L, null);
            case "resume", "play" -> new ConsolePlaybackCommand(Type.RESUME, 0L, null);
            case "seek", "s" -> new ConsolePlaybackCommand(Type.SEEK_ABSOLUTE,
                    PlaybackArguments.parseTimeMicros(requireRemainder(verb, remainder)),
                    null);
            case "status", "i", "info" -> new ConsolePlaybackCommand(Type.STATUS, 0L, null);
            case "open", "o" -> new ConsolePlaybackCommand(Type.OPEN,
                    0L,
                    remainder.isEmpty() ? null : PlaybackArguments.stripOuterQuotes(remainder));
            case "list", "l" -> new ConsolePlaybackCommand(Type.LIST, 0L, null);
            case "help", "h", "?" -> new ConsolePlaybackCommand(Type.HELP, 0L, null);
            case "quit", "q", "exit" -> new ConsolePlaybackCommand(Type.QUIT, 0L, null);
            default -> throw new IllegalArgumentException(
                    "Unknown command '" + verb + "'. Enter 'help' to list commands.");
        };
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }

        return -1;
    }

    private static String requireRemainder(String command, String remainder) {
        if (remainder.isEmpty()) {
            throw new IllegalArgumentException(command + " requires a target time.");
        }

        return remainder;
    }

    static String helpText() {
        return """
                Console commands:
                  p | pause | resume  Pause, resume, or toggle playback
                  seek <time>         Jump to seconds, mm:ss, or hh:mm:ss
                  +<time> | -<time>   Move forwards or backwards
                  status              Print the current playback position
                  open [path]         Play another file; omit path for the menu
                  list                List FLAC/Ogg FLAC files in this directory
                  help                Show these commands
                  q | quit            Stop playback and exit
                """;
    }
}
