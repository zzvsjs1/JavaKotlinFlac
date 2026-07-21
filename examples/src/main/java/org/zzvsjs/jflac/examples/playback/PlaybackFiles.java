package org.zzvsjs.jflac.examples.playback;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** File discovery, path resolution, and selection rules for both frontends. */
final class PlaybackFiles {
    private PlaybackFiles() {
    }

    static Path resolveAgainstWorkingDirectory(Path input, Path workingDirectory) {
        if (input == null) {
            return null;
        }

        Path base = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath()
                .normalize();
        return input.isAbsolute() ? input.normalize() : base.resolve(input).normalize();
    }

    static Path validateInputPath(Path input) {
        if (input == null) {
            throw new IllegalArgumentException("Input music file must not be null.");
        }

        Path normalised = input.normalize();
        if (!Files.isRegularFile(normalised)) {
            throw new IllegalArgumentException("Input music file does not exist: " + normalised);
        }

        return normalised;
    }

    static List<Path> listMusicFiles(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        try (var paths = Files.list(directory)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(PlaybackFiles::hasSupportedMusicExtension)
                    .map(Path::normalize)
                    .sorted(Comparator.comparing(
                            path -> path.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER
                    ))
                    .toList();
        } catch (UncheckedIOException error) {
            /*
             * Directory streams can report traversal failures lazily from the
             * terminal operation. Convert that wrapper back to the checked
             * contract so console LIST/OPEN can handle it as a local command
             * failure instead of stopping otherwise healthy playback.
             */
            throw error.getCause();
        }
    }

    static boolean hasSupportedMusicExtension(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }

        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".flac") || name.endsWith(".oga") || name.endsWith(".ogg");
    }

    static Path resolveMusicChoice(
            String rawChoice,
            List<Path> candidates,
            Path workingDirectory
    ) {
        Objects.requireNonNull(candidates, "candidates");
        String choice = PlaybackArguments.stripOuterQuotes(
                rawChoice == null ? "" : rawChoice.trim()
        ).trim();
        if (choice.isEmpty()) {
            throw new IllegalArgumentException("Enter a file number or path.");
        }

        if (choice.chars().allMatch(Character::isDigit)) {
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException(
                        "No music files are listed; enter a file path instead."
                );
            }

            try {
                int number = Integer.parseInt(choice);
                if (number >= 1 && number <= candidates.size()) {
                    return candidates.get(number - 1);
                }
            } catch (NumberFormatException ignored) {
                // The range message also explains an integer that is too large.
            }

            throw new IllegalArgumentException(
                    "File number must be between 1 and " + candidates.size() + "."
            );
        }

        return resolveAgainstWorkingDirectory(Path.of(choice), workingDirectory);
    }

    static Path validateDialogSelection(File selected) {
        if (selected == null) {
            return null;
        }

        Path selectedPath = selected.toPath().normalize();
        if (!Files.isRegularFile(selectedPath)) {
            throw new IllegalArgumentException(
                    "The selected path is not a regular file: " + selectedPath
            );
        }

        if (!hasSupportedMusicExtension(selectedPath)) {
            throw new IllegalArgumentException("Select a .flac, .oga, or .ogg file.");
        }

        return selectedPath;
    }
}
