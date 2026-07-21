package org.zzvsjs.jflac.examples.playback;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class PlaybackDialogSelectionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    public void cancellationReturnsNoSelection() {
        assertNull(PlaybackFiles.validateDialogSelection(null));
    }

    @Test
    public void validFlacAndOggFilesRetainPlatformPaths() throws Exception {
        Path flac = Files.createFile(temporaryDirectory.resolve("Música 測試.FLAC"));
        Path ogg = Files.createFile(temporaryDirectory.resolve("track.oga"));

        assertEquals(flac, PlaybackFiles.validateDialogSelection(flac.toFile()));
        assertEquals(ogg, PlaybackFiles.validateDialogSelection(ogg.toFile()));
    }

    @Test
    public void directoriesMissingFilesAndUnsupportedExtensionsAreRejected() throws Exception {
        Path text = Files.createFile(temporaryDirectory.resolve("notes.txt"));

        assertThrows(
                IllegalArgumentException.class,
                () -> PlaybackFiles.validateDialogSelection(temporaryDirectory.toFile())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> PlaybackFiles.validateDialogSelection(
                        temporaryDirectory.resolve("missing.flac").toFile()
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> PlaybackFiles.validateDialogSelection(text.toFile())
        );
    }
}
