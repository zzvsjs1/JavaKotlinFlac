package org.zzvsjs.jflac.examples;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class PlaybackFileSelectionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    public void cancellationReturnsNoSelection() {
        assertNull(PlaybackFileSelection.validate(null));
    }

    @Test
    public void validFlacAndOggFilesRetainPlatformPaths() throws Exception {
        Path flac = Files.createFile(temporaryDirectory.resolve("Música 測試.FLAC"));
        Path ogg = Files.createFile(temporaryDirectory.resolve("track.oga"));

        assertEquals(flac, PlaybackFileSelection.validate(flac.toFile()));
        assertEquals(ogg, PlaybackFileSelection.validate(ogg.toFile()));
    }

    @Test
    public void directoriesMissingFilesAndUnsupportedExtensionsAreRejected() throws Exception {
        Path text = Files.createFile(temporaryDirectory.resolve("notes.txt"));

        assertThrows(
                IllegalArgumentException.class,
                () -> PlaybackFileSelection.validate(temporaryDirectory.toFile())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> PlaybackFileSelection.validate(temporaryDirectory.resolve("missing.flac").toFile())
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> PlaybackFileSelection.validate(text.toFile())
        );
    }
}

