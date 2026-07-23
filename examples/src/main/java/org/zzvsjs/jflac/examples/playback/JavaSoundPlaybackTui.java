package org.zzvsjs.jflac.examples.playback;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowListenerAdapter;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.ExtendedTerminal;
import com.googlecode.lanterna.terminal.MouseCaptureMode;
import com.googlecode.lanterna.terminal.Terminal;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lanterna-based terminal user interface for the Java Sound player. */
final class JavaSoundPlaybackTui {
    private static final long REFRESH_MILLIS = 100L;
    private static final long TEN_SECONDS_MICROS = 10_000_000L;

    private final PlaybackSessionFactory sessionFactory;
    private final PlaybackTerminalFactory terminalFactory;

    private MultiWindowTextGUI textGUI;
    private BasicWindow window;
    private PlaybackController controller;
    private Path workingDirectory;
    private ScheduledExecutorService refreshExecutor;
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    private Label trackLabel;
    private Label stateLabel;
    private Label decodedFormatLabel;
    private Label outputFormatLabel;
    private Label timeLabel;
    private Label statusLabel;
    private PlaybackSeekBar seekBar;
    private Button playPauseButton;
    private Button stopButton;
    private Button backwardsButton;
    private Button forwardsButton;
    private String notice;
    private boolean initialParametersPending;
    private long pendingInitialStartMicros;
    private boolean pendingInitialPaused;

    JavaSoundPlaybackTui(PlaybackSessionFactory sessionFactory) {
        this(sessionFactory, JLineLanternaTerminal::open);
    }

    JavaSoundPlaybackTui(
            PlaybackSessionFactory sessionFactory,
            PlaybackTerminalFactory terminalFactory
    ) {
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
        this.terminalFactory = Objects.requireNonNull(terminalFactory, "terminalFactory");
    }

    static int run(PlaybackArguments options, Path workingDirectory) throws Exception {
        return new JavaSoundPlaybackTui(JavaSoundPlaybackSession::new).runPlayer(options, workingDirectory);
    }

    int runPlayer(PlaybackArguments options, Path workingDirectory) throws Exception {
        Objects.requireNonNull(options, "options");
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                .toAbsolutePath()
                .normalize();
        shuttingDown.set(false);
        refreshQueued.set(false);
        notice = null;
        initialParametersPending = false;

        try (TerminalScreen screen = createTerminalScreen()) {
            screen.startScreen();
            textGUI = new MultiWindowTextGUI(screen);
            // Polling keeps terminal input and periodic playback snapshots on a
            // single Lanterna GUI thread without blocking native resize events.
            textGUI.setBlockingIO(false);
            PlaybackController openedController = new PlaybackController(sessionFactory);
            controller = openedController;
            try (openedController) {
                buildWindow();
                startRefreshLoop();
                try {
                    if (options.input() == null) {
                        initialParametersPending = true;
                        pendingInitialStartMicros = options.startMicros();
                        pendingInitialPaused = options.startPaused();
                        textGUI.getGUIThread().invokeLater(this::chooseFile);
                    } else {
                        textGUI.getGUIThread().invokeLater(() -> openFile(
                                options.input(),
                                options.startMicros(),
                                options.startPaused()
                        ));
                    }

                    textGUI.addWindowAndWait(window);
                } finally {
                    shuttingDown.set(true);
                    stopRefreshLoop();
                }
            }
        }

        return 0;
    }

    private void buildWindow() {
        window = new BasicWindow("jflac Java Sound Playback");
        window.setHints(List.of(Window.Hint.EXPANDED, Window.Hint.FIT_TERMINAL_WINDOW));

        Panel content = new Panel(new GridLayout(1));
        content.addComponent(new Label("FLAC / Ogg FLAC terminal player").addStyle(SGR.BOLD));

        trackLabel = wrappingLabel("Track: no file selected");
        stateLabel = wrappingLabel("State: stopped");
        decodedFormatLabel = wrappingLabel("Decoded format: --");
        outputFormatLabel = wrappingLabel("Output format: --");
        timeLabel = wrappingLabel("Position: 00:00 / --:--");
        statusLabel = wrappingLabel("Status: choose Open or press O to select a file.");

        content.addComponent(trackLabel);
        content.addComponent(stateLabel);
        content.addComponent(decodedFormatLabel);
        content.addComponent(outputFormatLabel);
        content.addComponent(timeLabel);

        seekBar = new PlaybackSeekBar(this::seekToMicros);
        seekBar.setLayoutData(GridLayout.createHorizontallyFilledLayoutData());
        content.addComponent(seekBar);

        Panel buttons = new Panel(new GridLayout(4));
        addButton(buttons, new Button("Open", this::chooseFile));
        playPauseButton = new Button("Play", this::togglePlayPause);
        addButton(buttons, playPauseButton);
        stopButton = new Button("Stop", this::stopPlayback);
        addButton(buttons, stopButton);
        addButton(buttons, new Button("Help", this::showHelp));

        backwardsButton = new Button("-10 sec", () -> seekByMicros(-TEN_SECONDS_MICROS));
        addButton(buttons, backwardsButton);
        forwardsButton = new Button("+10 sec", () -> seekByMicros(TEN_SECONDS_MICROS));
        addButton(buttons, forwardsButton);
        addButton(buttons, new Button("Quit", this::quit));
        buttons.addComponent(new Label(""));
        buttons.setLayoutData(GridLayout.createHorizontallyFilledLayoutData());
        content.addComponent(buttons);

        content.addComponent(wrappingLabel(
                "Keys: O open | Space/P play-pause | S stop | J/L +/-10 sec | "
                        + "Tab focus | timeline arrows/Home/End/Page keys | Q/Esc quit"
        ));
        content.addComponent(statusLabel);

        window.setComponent(content);
        window.addWindowListener(new WindowListenerAdapter() {
            @Override
            public void onInput(Window source, KeyStroke keyStroke, AtomicBoolean deliverEvent) {
                handleWindowInput(source, keyStroke, deliverEvent);
            }
        });
        render(controller.viewState());
    }

    private static Label wrappingLabel(String text) {
        return new Label(text)
                .setLabelWidth(90)
                .setLayoutData(GridLayout.createHorizontallyFilledLayoutData());
    }

    private static void addButton(Panel panel, Component component) {
        component.setLayoutData(GridLayout.createHorizontallyFilledLayoutData());
        panel.addComponent(component);
    }

    private void handleWindowInput(Window source, KeyStroke keyStroke, AtomicBoolean deliverEvent) {
        if (keyStroke instanceof MouseAction mouseAction
                && mouseAction.isMouseUp()
                && seekBar.isDragging()) {
            // Lanterna routes drag events back to the component that received
            // mouse-down, but a release outside that component is unhandled.
            // Finishing here preserves clamping at both timeline endpoints.
            seekBar.finishDragAtGlobal(mouseAction.getPosition());
        }

        Interactable focused = source.getFocusedInteractable();
        PlaybackShortcut shortcut = PlaybackShortcut.from(keyStroke, focused instanceof Button);
        switch (shortcut) {
            case OPEN -> chooseFile();
            case TOGGLE_PLAY_PAUSE -> togglePlayPause();
            case STOP -> stopPlayback();
            case SEEK_BACKWARDS -> seekByMicros(-TEN_SECONDS_MICROS);
            case SEEK_FORWARDS -> seekByMicros(TEN_SECONDS_MICROS);
            case HELP -> showHelp();
            case QUIT -> quit();
            case NONE -> {
                return;
            }
        }

        deliverEvent.set(false);
    }

    private void chooseFile() {
        try {
            seekBar.cancelDrag();
            PlaybackViewState view = controller.viewState();
            Path initial = view.input() == null ? workingDirectory : view.input();
            File selected = new PlaybackFileDialog(
                    initial.toFile(),
                    textGUI.getScreen().getTerminalSize()
            ).showDialog(textGUI);
            Path selectedPath = PlaybackFiles.validateDialogSelection(selected);
            if (selectedPath == null) {
                setNotice("File selection cancelled; current playback is unchanged.");
                render(controller.viewState());
                return;
            }

            long startMicros = initialParametersPending ? pendingInitialStartMicros : 0L;
            boolean paused = initialParametersPending && pendingInitialPaused;
            initialParametersPending = false;
            openFile(selectedPath, startMicros, paused);
        } catch (RuntimeException error) {
            showError("File selection failed.", error);
        }
    }

    private void openFile(Path input, long startMicros, boolean paused) {
        seekBar.cancelDrag();
        Path resolvedInput = PlaybackFiles.resolveAgainstWorkingDirectory(
                input,
                workingDirectory
        );
        runAction(
                () -> controller.open(resolvedInput, startMicros, paused),
                "Opened " + displayName(resolvedInput) + (paused ? " (paused)." : "."),
                "Could not open " + displayName(resolvedInput) + "."
        );
    }

    private void togglePlayPause() {
        runAction(controller::togglePlayPause, null, "Could not change playback state.");
    }

    private void stopPlayback() {
        seekBar.cancelDrag();
        runAction(controller::stop, "Playback stopped.", "Could not stop playback.");
    }

    private void seekToMicros(long targetMicros) {
        runAction(
                () -> controller.seekToMicros(targetMicros),
                "Seeking to " + PlaybackArguments.formatTime(targetMicros) + ".",
                "Could not seek to the selected position."
        );
    }

    private void seekByMicros(long deltaMicros) {
        runAction(
                () -> controller.seekByMicros(deltaMicros),
                deltaMicros < 0L ? "Seeking backwards." : "Seeking forwards.",
                "Could not seek."
        );
    }

    private void runAction(UiAction action, String successNotice, String failurePrefix) {
        try {
            PlaybackViewState view = action.run();
            setNotice(successNotice);
            render(view);
        } catch (Exception | IOError | LinkageError error) {
            showError(failurePrefix, error);
        }
    }

    private void showHelp() {
        MessageDialog.showMessageDialog(
                textGUI,
                "Playback controls", """
                        Mouse:
                          Select files and activate buttons with the left button.
                          Click the timeline to seek. Drag it to preview, then release once to seek.

                        Keyboard:
                          O open, Space/P play-pause, S stop, J/L back/forward 10 seconds.
                          Tab selects controls. On the timeline use Left/Right (5 seconds),
                          Page Up/Down (30 seconds), Home/End, then Q or Escape to quit.""",
                MessageDialogButton.OK
        );
    }

    private void showError(String prefix, Throwable error) {
        String message = error == null
                ? prefix
                : prefix + " " + PlaybackMessages.rootCauseMessage(error);
        setNotice("Error: " + message);
        render(controller.viewState());
        MessageDialog.showMessageDialog(
                textGUI,
                "Playback error",
                message,
                MessageDialogButton.OK
        );
    }

    private void startRefreshLoop() {
        refreshExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jflac-playback-tui-refresh");
            thread.setDaemon(true);
            return thread;
        });

        refreshExecutor.scheduleAtFixedRate(
                this::queueRefresh,
                0L,
                REFRESH_MILLIS,
                TimeUnit.MILLISECONDS
        );
    }

    private void queueRefresh() {
        if (shuttingDown.get() || !refreshQueued.compareAndSet(false, true)) {
            return;
        }

        try {
            textGUI.getGUIThread().invokeLater(() -> {
                refreshQueued.set(false);
                if (!shuttingDown.get() && window.getTextGUI() != null) {
                    render(controller.viewState());
                }
            });
        } catch (IllegalStateException ignored) {
            refreshQueued.set(false);
        }
    }

    private void stopRefreshLoop() {
        if (refreshExecutor != null) {
            refreshExecutor.shutdownNow();
            refreshExecutor = null;
        }
    }

    private void render(PlaybackViewState view) {
        Path input = view.input();
        trackLabel.setText("Track: " + (input == null ? "no file selected" : input.toString()));
        stateLabel.setText("State: " + displayState(view));
        decodedFormatLabel.setText("Decoded format: " + displayFormat(view.sourceFormat()));
        outputFormatLabel.setText("Output format: " + displayFormat(view.playbackFormat()));

        String duration = view.durationMicros() < 0L
                ? "--:--"
                : PlaybackArguments.formatTime(view.durationMicros());
        long displayedPosition = seekBar.isDragging()
                ? seekBar.displayedPositionMicros()
                : view.positionMicros();
        timeLabel.setText(
                "Position: " + PlaybackArguments.formatTime(displayedPosition) + " / " + duration
        );

        seekBar.setTimeline(view.positionMicros(), view.durationMicros(), view.canSeek());
        boolean canControl = view.canControl();
        backwardsButton.setEnabled(canControl);
        forwardsButton.setEnabled(canControl);
        stopButton.setEnabled(view.sessionOpen());
        playPauseButton.setEnabled(view.input() != null);
        playPauseButton.setLabel(view.playPauseLabel());

        if (view.failure() != null) {
            statusLabel.setText("Status: playback failed: "
                    + PlaybackMessages.rootCauseMessage(view.failure()));
        } else if (notice != null && !notice.isBlank()) {
            statusLabel.setText("Status: " + notice);
        } else if (!view.sessionOpen()) {
            statusLabel.setText(view.input() == null
                    ? "Status: choose Open or press O to select a file."
                    : "Status: stopped; choose Play to restart the selected track.");
        } else {
            statusLabel.setText("Status: " + displayState(view) + ".");
        }
    }

    private void setNotice(String message) {
        notice = message;
    }

    private void quit() {
        shuttingDown.set(true);
        seekBar.cancelDrag();
        window.close();
    }

    private TerminalScreen createTerminalScreen() throws IOException {
        Terminal terminal = terminalFactory.open();
        try {
            if (terminal instanceof ExtendedTerminal extendedTerminal) {
                extendedTerminal.setTitle("jflac Java Sound Playback");
                extendedTerminal.setMouseCaptureMode(MouseCaptureMode.CLICK_RELEASE_DRAG);
            }

            return new TerminalScreen(terminal);
        } catch (IOException | RuntimeException | IOError | LinkageError error) {
            try {
                terminal.close();
            } catch (IOException | RuntimeException | IOError | LinkageError closeError) {
                if (closeError != error) {
                    error.addSuppressed(closeError);
                }
            }

            throw error;
        }
    }

    private static String displayState(PlaybackViewState view) {
        if (!view.sessionOpen()) {
            return "stopped";
        }

        return view.state().name().toLowerCase(Locale.ROOT);
    }

    private static String displayFormat(javax.sound.sampled.AudioFormat format) {
        return format == null ? "--" : PlaybackAudioOutput.describeAudioFormat(format);
    }

    private static String displayName(Path input) {
        Path fileName = input.getFileName();
        return fileName == null ? input.toString() : fileName.toString();
    }

    @FunctionalInterface
    private interface UiAction {
        PlaybackViewState run() throws Exception;
    }
}
