package org.zzvsjs.jflac.examples;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/** Pure validation boundary for results returned by Lanterna's file dialog. */
final class PlaybackFileSelection {
    private PlaybackFileSelection() {
    }

    static Path validate(File selected) {
        if (selected == null) {
            return null;
        }

        Path selectedPath = selected.toPath().normalize();
        if (!Files.isRegularFile(selectedPath)) {
            throw new IllegalArgumentException(
                    "The selected path is not a regular file: " + selectedPath
            );
        }
        if (!JavaSoundPlaybackDemo.hasSupportedMusicExtension(selectedPath)) {
            throw new IllegalArgumentException("Select a .flac, .oga, or .ogg file.");
        }
        return selectedPath;
    }
}

