package org.zzvsjs.jflac.examples.playback;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class PlaybackFilesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    public void inputValidationRequiresAnExistingRegularFile() throws Exception {
        IllegalArgumentException nullError = assertThrows(
                IllegalArgumentException.class,
                () -> PlaybackFiles.validateInputPath(null)
        );
        IllegalArgumentException missingError = assertThrows(
                IllegalArgumentException.class,
                () -> PlaybackFiles.validateInputPath(
                        temporaryDirectory.resolve("missing.flac")
                )
        );
        Path file = Files.createFile(temporaryDirectory.resolve("music.flac"));

        assertEquals("Input music file must not be null.", nullError.getMessage());
        assertTrue(missingError.getMessage().contains("missing.flac"));
        assertEquals(file, PlaybackFiles.validateInputPath(file));
    }

    @Test
    public void musicFileMenuFiltersAndSortsSupportedExtensions() throws Exception {
        Path second = Files.createFile(temporaryDirectory.resolve("b.oga"));
        Path first = Files.createFile(temporaryDirectory.resolve("A.flac"));
        Files.createFile(temporaryDirectory.resolve("notes.txt"));
        Files.createDirectory(temporaryDirectory.resolve("folder.flac"));

        assertEquals(
                List.of(first, second),
                PlaybackFiles.listMusicFiles(temporaryDirectory)
        );
    }

    @Test
    public void musicChoiceAcceptsMenuNumberAndWorkingDirectoryRelativePath() {
        List<Path> candidates = List.of(Path.of("first.flac"), Path.of("second.oga"));

        assertEquals(
                Path.of("second.oga"),
                PlaybackFiles.resolveMusicChoice(
                        "2",
                        candidates,
                        temporaryDirectory
                )
        );
        assertEquals(
                temporaryDirectory.resolve(Path.of("folder with spaces", "song.flac")),
                PlaybackFiles.resolveMusicChoice(
                        "\"folder with spaces/song.flac\"",
                        candidates,
                        temporaryDirectory
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> PlaybackFiles.resolveMusicChoice(
                        "3",
                        candidates,
                        temporaryDirectory
                )
        );
    }

    @Test
    public void numericChoiceExplainsWhenTheMenuIsEmpty() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> PlaybackFiles.resolveMusicChoice(
                        "1",
                        List.of(),
                        temporaryDirectory
                )
        );

        assertEquals(
                "No music files are listed; enter a file path instead.",
                error.getMessage()
        );
    }

    @Test
    public void supportedMusicExtensionsAreCaseInsensitiveAndNullSafe() {
        assertTrue(PlaybackFiles.hasSupportedMusicExtension(Path.of("track.FLAC")));
        assertTrue(PlaybackFiles.hasSupportedMusicExtension(Path.of("track.OgA")));
        assertTrue(PlaybackFiles.hasSupportedMusicExtension(Path.of("track.OGG")));
        assertFalse(PlaybackFiles.hasSupportedMusicExtension(Path.of("track.wav")));
        assertFalse(PlaybackFiles.hasSupportedMusicExtension(null));
    }
}
