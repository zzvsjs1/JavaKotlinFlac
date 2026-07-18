package org.zzvsjs.jflac.examples;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class JavaSoundPlaybackDemoTest {
    @Test
    public void validateInputPathRejectsNull() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> JavaSoundPlaybackDemo.validateInputPath(null)
        );

        assertEquals("Input music file must not be null.", error.getMessage());
    }

    @Test
    public void validateInputPathRequiresRegularFile() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> JavaSoundPlaybackDemo.validateInputPath(Path.of("missing.flac"))
        );

        assertEquals("Input music file does not exist: missing.flac", error.getMessage());
    }

    @Test
    public void validateInputPathAcceptsExistingFile() throws Exception {
        Path file = Files.createTempFile("jflac-java-sound-playback", ".flac");
        try {
            assertEquals(file.normalize(), JavaSoundPlaybackDemo.validateInputPath(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void musicFileMenuFiltersAndSortsSupportedExtensions() throws Exception {
        Path directory = Files.createTempDirectory("jflac-playback-menu");
        try {
            Path second = Files.createFile(directory.resolve("b.oga"));
            Path first = Files.createFile(directory.resolve("A.flac"));
            Files.createFile(directory.resolve("notes.txt"));
            Files.createDirectory(directory.resolve("folder.flac"));

            assertEquals(List.of(first, second), JavaSoundPlaybackDemo.listMusicFiles(directory));
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                                // Test cleanup must not obscure the assertion result.
                            }
                        });
            }
        }
    }

    @Test
    public void musicChoiceAcceptsMenuNumberAndQuotedPath() {
        List<Path> candidates = List.of(Path.of("first.flac"), Path.of("second.oga"));

        assertEquals(Path.of("second.oga"), JavaSoundPlaybackDemo.resolveMusicChoice("2", candidates));
        assertEquals(
                Path.of("folder with spaces", "song.flac"),
                JavaSoundPlaybackDemo.resolveMusicChoice("\"folder with spaces/song.flac\"", candidates)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> JavaSoundPlaybackDemo.resolveMusicChoice("3", candidates)
        );
    }

    @Test
    public void helpAndInteractiveEndOfInputDoNotOpenAnAudioDevice() throws Exception {
        ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(outputBytes, true, StandardCharsets.UTF_8);
             PrintStream error = new PrintStream(errorBytes, true, StandardCharsets.UTF_8)) {
            assertEquals(
                    0,
                    JavaSoundPlaybackDemo.run(
                            new String[] { "--help" },
                            new ByteArrayInputStream(new byte[0]),
                            output,
                            error,
                            Path.of(".")
                    )
            );
            assertEquals(
                    2,
                    JavaSoundPlaybackDemo.run(
                            new String[] { "--console" },
                            new ByteArrayInputStream(new byte[0]),
                            output,
                            error,
                            Path.of(".")
                    )
            );
            assertEquals(
                    0,
                    JavaSoundPlaybackDemo.run(
                            new String[] { "--console" },
                            new ByteArrayInputStream("q\n".getBytes(StandardCharsets.UTF_8)),
                            output,
                            error,
                            Path.of(".")
                    )
            );
        }

        assertTrue(outputBytes.toString(StandardCharsets.UTF_8).contains("Usage: JavaSoundPlaybackDemo"));
        assertTrue(errorBytes.toString(StandardCharsets.UTF_8).contains("No music file was selected"));
    }

    @Test
    public void defaultModeDispatchesToTuiWithoutOpeningAudioInTheLauncher() throws Exception {
        AtomicReference<PlaybackArguments> launchedOptions = new AtomicReference<>();
        AtomicReference<Path> launchedDirectory = new AtomicReference<>();
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();

        try (PrintStream error = new PrintStream(errorBytes, true, StandardCharsets.UTF_8)) {
            int result = JavaSoundPlaybackDemo.run(
                    new String[] { "--file", "music.flac", "--start", "12.5", "--paused" },
                    new ByteArrayInputStream(new byte[0]),
                    System.out,
                    error,
                    Path.of("demo-directory"),
                    (options, workingDirectory) -> {
                        launchedOptions.set(options);
                        launchedDirectory.set(workingDirectory);
                        return 23;
                    }
            );

            assertEquals(23, result);
        }

        assertEquals(Path.of("music.flac"), launchedOptions.get().input());
        assertEquals(12_500_000L, launchedOptions.get().startMicros());
        assertTrue(launchedOptions.get().startPaused());
        assertEquals(PlaybackArguments.Mode.AUTO, launchedOptions.get().mode());
        assertEquals(Path.of("demo-directory"), launchedDirectory.get());
        assertEquals("", errorBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void automaticModeFallsBackOnlyWhenNoNativeTerminalIsAvailable() throws Exception {
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
        try (PrintStream error = new PrintStream(errorBytes, true, StandardCharsets.UTF_8)) {
            int result = JavaSoundPlaybackDemo.run(
                    new String[0],
                    new ByteArrayInputStream("q\n".getBytes(StandardCharsets.UTF_8)),
                    System.out,
                    error,
                    Path.of("."),
                    (options, workingDirectory) -> {
                        throw new TerminalUnavailableException("test terminal is unavailable");
                    }
            );

            assertEquals(0, result);
        }

        assertTrue(errorBytes.toString(StandardCharsets.UTF_8)
                .contains("using the line-command interface"));
    }

    @Test
    public void forcedTuiDoesNotFallBackToLineCommands() throws Exception {
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
        try (PrintStream error = new PrintStream(errorBytes, true, StandardCharsets.UTF_8)) {
            int result = JavaSoundPlaybackDemo.run(
                    new String[] { "--tui" },
                    new ByteArrayInputStream("q\n".getBytes(StandardCharsets.UTF_8)),
                    System.out,
                    error,
                    Path.of("."),
                    (options, workingDirectory) -> {
                        throw new TerminalUnavailableException("test terminal is unavailable");
                    }
            );

            assertEquals(1, result);
        }

        String errorText = errorBytes.toString(StandardCharsets.UTF_8);
        assertTrue(errorText.contains("Native terminal unavailable"));
        assertTrue(errorText.contains("does not open an AWT/Swing emulator"));
    }

    @Test
    public void automaticModeDoesNotHideOrdinaryTuiFailures() throws Exception {
        InputStream unreadableInput = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("Ordinary TUI failures must not enter the console loop");
            }
        };
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
        try (PrintStream error = new PrintStream(errorBytes, true, StandardCharsets.UTF_8)) {
            int result = JavaSoundPlaybackDemo.run(
                    new String[0],
                    unreadableInput,
                    System.out,
                    error,
                    Path.of("."),
                    (options, workingDirectory) -> {
                        throw new java.io.IOException("test TUI failure");
                    }
            );

            assertEquals(1, result);
        }

        String errorText = errorBytes.toString(StandardCharsets.UTF_8);
        assertTrue(errorText.contains("Terminal playback failed: test TUI failure"));
        assertTrue(errorText.contains("Try --console"));
    }

    @Test
    public void supportedMusicExtensionsAreCaseInsensitive() {
        assertTrue(JavaSoundPlaybackDemo.hasSupportedMusicExtension(Path.of("track.FLAC")));
        assertTrue(JavaSoundPlaybackDemo.hasSupportedMusicExtension(Path.of("track.OgA")));
        assertTrue(JavaSoundPlaybackDemo.hasSupportedMusicExtension(Path.of("track.OGG")));
    }

    @Test
    public void headlessModeDoesNotReadStandardInputOrOpenASoundDevice() throws Exception {
        Path input = Files.createTempFile("jflac-headless-player", ".flac");
        FakePlaybackSession fakeSession = new FakePlaybackSession(input, true);
        InputStream unreadableInput = new InputStream() {
            @Override
            public int read() {
                throw new AssertionError("--no-controls must not read standard input");
            }
        };

        try {
            assertEquals(
                    0,
                    JavaSoundPlaybackDemo.run(
                            new String[] { "--no-controls", input.toString() },
                            unreadableInput,
                            System.out,
                            System.err,
                            input.getParent(),
                            (options, workingDirectory) -> {
                                throw new AssertionError("Headless mode must not launch the TUI");
                            },
                            ignored -> fakeSession
                    )
            );
        } finally {
            Files.deleteIfExists(input);
        }

        assertTrue(fakeSession.started);
        assertTrue(fakeSession.closed);
    }

    @Test
    public void consoleLoopDispatchesPauseSeekAndQuitToAFakeSession() throws Exception {
        Path input = Files.createTempFile("jflac-console-player", ".flac");
        FakePlaybackSession fakeSession = new FakePlaybackSession(input, false);
        try {
            assertEquals(
                    0,
                    JavaSoundPlaybackDemo.run(
                            new String[] { "--console", input.toString() },
                            new ByteArrayInputStream("p\n+10\nq\n".getBytes(StandardCharsets.UTF_8)),
                            System.out,
                            System.err,
                            input.getParent(),
                            (options, workingDirectory) -> {
                                throw new AssertionError("Console mode must not launch the TUI");
                            },
                            ignored -> fakeSession
                    )
            );
        } finally {
            Files.deleteIfExists(input);
        }

        assertEquals(1, fakeSession.toggleCount);
        assertEquals(List.of(10_000_000L), fakeSession.relativeSeeks);
        assertTrue(fakeSession.closed);
    }

    @Test
    public void selectPlaybackFormatKeepsSupportedDecodedFormat() throws Exception {
        AudioFormat decoded = pcm(44_100f, 16, 2);

        AudioFormat selected = JavaSoundPlaybackDemo.selectPlaybackFormat(
                decoded,
                new JavaSoundPlaybackDemo.PlaybackSupport() {
                    @Override
                    public boolean isLineSupported(AudioFormat format) {
                        return format.equals(decoded);
                    }

                    @Override
                    public boolean isConversionSupported(AudioFormat target, AudioFormat source) {
                        return false;
                    }
                }
        );

        assertEquals(decoded, selected);
    }

    @Test
    public void selectPlaybackFormatFallsBackToConvertedSixteenBitPcm() throws Exception {
        AudioFormat decoded = pcm(96_000f, 24, 2);

        AudioFormat selected = JavaSoundPlaybackDemo.selectPlaybackFormat(
                decoded,
                new JavaSoundPlaybackDemo.PlaybackSupport() {
                    @Override
                    public boolean isLineSupported(AudioFormat format) {
                        return format.getSampleSizeInBits() == 16
                                && format.getChannels() == 2
                                && format.getSampleRate() == 48_000f;
                    }

                    @Override
                    public boolean isConversionSupported(AudioFormat target, AudioFormat source) {
                        return target.getSampleSizeInBits() == 16 && source.equals(decoded);
                    }
                }
        );

        assertPcmFormat(selected, 48_000f, 16, 2);
    }

    @Test
    public void selectPlaybackFormatFallsBackToConvertedThirtyTwoBitPcm() throws Exception {
        AudioFormat decoded = pcm(96_000f, 24, 2);

        AudioFormat selected = JavaSoundPlaybackDemo.selectPlaybackFormat(
                decoded,
                new JavaSoundPlaybackDemo.PlaybackSupport() {
                    @Override
                    public boolean isLineSupported(AudioFormat format) {
                        return format.getSampleSizeInBits() == 32
                                && format.getChannels() == 2
                                && format.getSampleRate() == 48_000f;
                    }

                    @Override
                    public boolean isConversionSupported(AudioFormat target, AudioFormat source) {
                        return target.getSampleSizeInBits() == 32 && source.equals(decoded);
                    }
                }
        );

        assertPcmFormat(selected, 48_000f, 32, 2);
    }

    @Test
    public void selectPlaybackFormatFallsBackToConvertedTwentyFourBitPcm() throws Exception {
        AudioFormat decoded = pcm(96_000f, 32, 2);

        AudioFormat selected = JavaSoundPlaybackDemo.selectPlaybackFormat(
                decoded,
                new JavaSoundPlaybackDemo.PlaybackSupport() {
                    @Override
                    public boolean isLineSupported(AudioFormat format) {
                        return format.getSampleSizeInBits() == 24
                                && format.getChannels() == 2
                                && format.getSampleRate() == 44_100f;
                    }

                    @Override
                    public boolean isConversionSupported(AudioFormat target, AudioFormat source) {
                        return target.getSampleSizeInBits() == 24 && source.equals(decoded);
                    }
                }
        );

        assertPcmFormat(selected, 44_100f, 24, 2);
    }

    @Test
    public void selectPlaybackFormatReportsUnsupportedOutput() {
        AudioFormat decoded = pcm(96_000f, 24, 2);

        LineUnavailableException error = assertThrows(
                LineUnavailableException.class,
                () -> JavaSoundPlaybackDemo.selectPlaybackFormat(
                        decoded,
                        new JavaSoundPlaybackDemo.PlaybackSupport() {
                            @Override
                            public boolean isLineSupported(AudioFormat format) {
                                return false;
                            }

                            @Override
                            public boolean isConversionSupported(AudioFormat target, AudioFormat source) {
                                return false;
                            }
                        }
                )
        );

        assertEquals("No output line supports decoded PCM format or the standard fallback formats: "
                + "96000 Hz, 2 channel(s), 24 bit, frame size 6 byte(s), little-endian, PCM_SIGNED", error.getMessage());
    }

    private static AudioFormat pcm(float sampleRate, int bitsPerSample, int channels) {
        int bytesPerSample = (bitsPerSample + 7) / 8;
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                bitsPerSample,
                channels,
                bytesPerSample * channels,
                sampleRate,
                false
        );
    }

    private static final class FakePlaybackSession implements PlaybackSession {
        private final Path input;
        private final boolean endImmediately;
        private final List<Long> relativeSeeks = new java.util.ArrayList<>();
        private State state = State.NEW;
        private boolean started;
        private boolean closed;
        private int toggleCount;

        private FakePlaybackSession(Path input, boolean endImmediately) {
            this.input = input;
            this.endImmediately = endImmediately;
        }

        @Override
        public Path input() {
            return input;
        }

        @Override
        public AudioFormat sourceFormat() {
            return pcm(44_100f, 16, 2);
        }

        @Override
        public AudioFormat playbackFormat() {
            return sourceFormat();
        }

        @Override
        public long totalSourceFrames() {
            return 5_292_000L;
        }

        @Override
        public void start(long startMicros, boolean paused) {
            started = true;
            state = endImmediately ? State.ENDED : paused ? State.PAUSED : State.PLAYING;
        }

        @Override
        public void togglePause() {
            toggleCount++;
            state = state == State.PAUSED ? State.PLAYING : State.PAUSED;
        }

        @Override
        public void pause() {
            state = State.PAUSED;
        }

        @Override
        public void resume() {
            state = State.PLAYING;
        }

        @Override
        public boolean isPauseRequested() {
            return state == State.PAUSED;
        }

        @Override
        public void seekToMicros(long targetMicros) {
        }

        @Override
        public void seekByMicros(long deltaMicros) {
            relativeSeeks.add(deltaMicros);
        }

        @Override
        public Snapshot snapshot() {
            return new Snapshot(input, state, 0L, 120_000_000L, null);
        }

        @Override
        public void close() {
            closed = true;
            state = State.CLOSED;
        }
    }

    private static void assertPcmFormat(
            AudioFormat format,
            float sampleRate,
            int bitsPerSample,
            int channels
    ) {
        assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding());
        assertEquals(sampleRate, format.getSampleRate());
        assertEquals(bitsPerSample, format.getSampleSizeInBits());
        assertEquals(channels, format.getChannels());
        assertEquals(((bitsPerSample + 7) / 8) * channels, format.getFrameSize());
        assertEquals(sampleRate, format.getFrameRate());
        assertEquals(false, format.isBigEndian());
    }
}
