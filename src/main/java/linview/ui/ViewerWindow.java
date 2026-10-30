package linview.ui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import java.nio.file.Path;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.geometry.Dimension2D;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import linview.files.FileActions;
import linview.image.Loaded;
import linview.image.Orientation;
import linview.image.thumbnail.ThumbnailLoader;
import linview.settings.Settings;
import linview.viewer.ImageList;
import linview.viewer.ViewerController;
import linview.viewer.Zoom;

/**
 * The window (#53): Stage and Scene, the Open dialog and full-screen, and what the controller shows. Every action goes
 * through {@link #execute}: keys, the taskbar and later the menu call it (REPORT.md §4.2). In full-screen the panels
 * (#27–#29) and the overlay (#45) float over the image. What an action does
 * to the viewing stays in {@link ViewerController}; this class only adds what belongs to the window.
 */
public final class ViewerWindow implements ViewerController.Display<Image> {

    /** Icon sizes in src/main/resources/linview/icons; the desktop picks the best one for each place. */
    static final int[] ICON_SIZES = {16, 32, 48, 64, 128, 256, 512};

    private final Stage stage;
    private final ViewerPane viewer = new ViewerPane();
    private final ThumbStrip strip;
    private final Taskbar taskbar;
    private final ViewerMenu menu = new ViewerMenu(this::execute, open -> viewer.menuOpen(open));
    /** The full-screen overlay (#45): {@code 2/16}, optionally with the file name. */
    private final Label overlay = new Label();
    private boolean taskbarOpen;
    private final FileActions files;
    private final ThumbnailLoader thumbnails;
    /**
     * Changes to files, one at a time and in order (Trash #34, saved rotations #51), off the JavaFX thread: a USB
     * drive can be slow. {@link #close} waits for them.
     */
    private final ExecutorService fileWork = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("linview-files").daemon().factory());
    /** The thumbnail strip's folder and selection, to write the overlay again after a settings change (#48). */
    private List<Path> shownFiles = List.of();
    private int shownIndex = -1;
    /** A failed settings save was reported in this run: once is enough (#48). */
    private boolean settingsProblemShown;
    /** A file is on its way to the Trash: Del waits for it, so the same file isn't sent twice (#34). */
    private boolean trashing;
    private Settings settings;
    private ViewerController<Image> controller;

    /** {@code thumbnails} fills the full-screen thumbnail strip (#28); {@code files} does Del (#34). */
    public ViewerWindow(Stage stage, ThumbnailLoader thumbnails, FileActions files) {
        this.stage = stage;
        this.files = files;
        this.thumbnails = thumbnails;
        strip = new ThumbStrip(thumbnails, index -> controller.select(index));
        taskbar = new Taskbar(this::execute, percent -> controller.zoomToPercent(percent), () -> viewer.requestFocus());
        overlay.getStyleClass().add("overlay");
    }

    static String iconPath(int size) {
        return "/linview/icons/linview-" + size + ".png";
    }

    /** The window's place and size as last seen outside full-screen, for the next start (#26). */
    private double windowX;
    private double windowY;
    private double windowWidth;
    private double windowHeight;

    /**
     * Builds the scene for {@code controller}, which is shown to, with the background colour of {@code settings}, and
     * opens the window as it was last closed (R-FS-02, #26): full-screen or at its place, on the mouse's monitor.
     */
    public void show(ViewerController<Image> controller, Settings settings) {
        this.controller = controller;
        viewer.connect(controller, stage);
        viewer.setBackgroundColour(settings.backgroundColour());

        this.settings = settings;
        EdgePanels panels = new EdgePanels(stage, viewer);
        panels.addTop(strip, strip::scrollToCurrent);
        panels.addBottomLeft(taskbar, taskbar::isTyping, open -> {
            taskbarOpen = open;
            if (!open && taskbar.isTyping()) {
                viewer.requestFocus();
            }
            updateOverlay();
        });
        StackPane overlayLayer = new StackPane(overlay);
        overlayLayer.setMouseTransparent(true);
        placeOverlay();
        StackPane.setMargin(overlay, new Insets(12, 16, 12, 16));
        stage.fullScreenProperty().addListener(obs -> updateOverlay());
        Dimension2D content = WindowPlacement.placeAtStart(stage, settings.windowX(), settings.windowY(),
                settings.windowWidth(), settings.windowHeight());
        Scene scene = new Scene(new StackPane(viewer, overlayLayer, panels.layer()), content.getWidth(),
                content.getHeight());
        scene.getStylesheets().add(ViewerWindow.class.getResource("/linview/viewer.css").toExternalForm());
        panels.watch(scene);
        menu.closeOnOutsidePress(scene, stage);
        scene.setOnKeyPressed(this::keyPressed);
        // A click on the image's left / right half goes back / on immediately (#62).
        viewer.onClicks(() -> execute(Keys.Action.PREVIOUS), () -> execute(Keys.Action.NEXT));
        // Right-click: the menu (#49); the image's gestures use the left button only.
        viewer.setOnContextMenuRequested(e -> menu.show(viewer, e.getScreenX(), e.getScreenY(),
                new ViewerMenu.State(controller.isImageShown(), controller.trashTarget() != null,
                        settings.zoomMode(), stage.isFullScreen())));

        stage.setFullScreenExitHint("");
        stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);   // Esc quits, also from full-screen (#26)
        stage.setTitle(ViewerController.TITLE);
        for (int size : ICON_SIZES) {
            stage.getIcons().add(new Image(ViewerWindow.class.getResource(iconPath(size)).toExternalForm()));
        }
        stage.setScene(scene);
        stage.show();
        rememberWindow(scene);
        stage.setOnCloseRequest(e -> controller.leaveImage());   // the window's close button (#51)
        stage.setOnHiding(e -> controller.windowClosed(stage.isFullScreen(), windowX, windowY, windowWidth,
                windowHeight));
        if (settings.fullScreen()) {
            stage.setFullScreen(true);   // on the monitor the window just opened on
        }
    }

    private void placeOverlay() {
        StackPane.setAlignment(overlay, switch (settings.overlayCorner()) {
            case BOTTOM_LEFT -> Pos.BOTTOM_LEFT;
            case BOTTOM_RIGHT -> Pos.BOTTOM_RIGHT;
            case TOP_LEFT -> Pos.TOP_LEFT;
            case TOP_RIGHT -> Pos.TOP_RIGHT;
        });
    }

    /** Follows the window's place and size, except in full-screen, where they are the screen's. */
    private void rememberWindow(Scene scene) {
        InvalidationListener changed = obs -> {
            if (!stage.isFullScreen() && !stage.isIconified()) {
                windowX = stage.getX();
                windowY = stage.getY();
                windowWidth = scene.getWidth();
                windowHeight = scene.getHeight();
            }
        };
        changed.invalidated(null);
        stage.xProperty().addListener(changed);
        stage.yProperty().addListener(changed);
        scene.widthProperty().addListener(changed);
        scene.heightProperty().addListener(changed);
    }

    private void keyPressed(KeyEvent e) {
        if (stage.getScene().getFocusOwner() instanceof TextInputControl) {
            return;   // typing in the taskbar's % box (§6.4)
        }
        Keys.Action action = Keys.actionFor(e);
        if (action != null) {
            execute(action);
        }
    }

    /** Carries out {@code action}, whichever control asked for it. */
    public void execute(Keys.Action action) {
        switch (action) {
            case NEXT -> controller.next();
            case PREVIOUS -> controller.previous();
            case FIRST -> controller.first();
            case LAST -> controller.last();
            case ARROW_LEFT -> controller.arrow(-1, 0);
            case ARROW_RIGHT -> controller.arrow(1, 0);
            case ARROW_UP -> controller.arrow(0, -1);
            case ARROW_DOWN -> controller.arrow(0, 1);
            case OPEN -> chooseAndOpen();
            case FULL_SCREEN -> stage.setFullScreen(!stage.isFullScreen());   // Enter, F11 (R-VIEW-02)
            case ZOOM_IN -> controller.zoomIn();
            case ZOOM_OUT -> controller.zoomOut();
            case FIT -> controller.setZoomMode(Zoom.Mode.FIT);
            case FIT_WIDTH -> controller.setZoomMode(Zoom.Mode.WIDTH);
            case FIT_HEIGHT -> controller.setZoomMode(Zoom.Mode.HEIGHT);
            case ACTUAL_SIZE -> controller.setZoomMode(Zoom.Mode.ACTUAL);
            case ZOOM_200 -> controller.zoomToPercent(200);
            case FIT_OR_ACTUAL -> controller.toggleFitOrActual();
            case ZOOM_LOCK -> controller.toggleZoomLock();
            case ROTATE_RIGHT -> controller.rotateRight();
            case ROTATE_LEFT -> controller.rotateLeft();
            case DELETE -> trashCurrent();
            case SETTINGS -> SettingsWindow.ask(stage, settings).ifPresent(this::apply);
            case SHORTCUTS -> ShortcutsWindow.show(stage);
            // Quits, also from full-screen, which the next start remembers (Marcos, 2026-10-24, #26).
            case ESCAPE -> {
                controller.leaveImage();   // a turn may be saved first (#51)
                stage.close();
            }
        }
    }

    /**
     * The settings window's OK (#48): the values go through the {@link Settings} setters, take effect on what is on
     * screen at once, and are saved.
     */
    private void apply(SettingsWindow.Choices choices) {
        settings.setWheel(choices.wheel());
        if (choices.background() != null) {
            settings.setBackground(choices.background());
        }
        settings.setCounterOverlay(choices.counter());
        settings.setFileNameOverlay(choices.fileName());
        settings.setOverlayCorner(choices.corner());
        settings.setRotationSave(choices.rotationSave());
        controller.saveSettings();
        controller.chooseZoomMode(choices.openMode());
        viewer.setBackgroundColour(settings.backgroundColour());
        placeOverlay();
        showPosition(shownFiles, shownIndex);
    }

    /** A settings save failed (R-SET-01): said once per run; the app keeps working and the old file stays. */
    public void settingsNotSaved(String problem) {
        if (!settingsProblemShown) {
            settingsProblemShown = true;
            showProblem("Settings", problem + "\n\nChanges apply until linview closes.");
        }
    }

    /** Waits for file changes still running, e.g. a rotation being saved when quitting (#51). */
    public void close() {
        fileWork.shutdown();
        try {
            fileWork.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Del (R-FILE-01, #34): asks, then moves the file to the Trash off the JavaFX thread (slow drives); nothing while
     * the next image is loading (review R7). Enter moves it, Esc cancels.
     */
    private void trashCurrent() {
        Path target = controller.trashTarget();   // kept while asking: navigating can't change it (R7)
        if (target == null || trashing) {
            return;
        }
        ButtonType move = new ButtonType("Move to Trash", ButtonBar.ButtonData.OK_DONE);
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION, "Move " + target.getFileName() + " to the Trash?",
                move, ButtonType.CANCEL);
        ask.initOwner(stage);
        ask.setTitle("Move to Trash");
        ask.setHeaderText(null);
        if (ask.showAndWait().orElse(ButtonType.CANCEL) != move) {
            return;
        }
        trashing = true;
        fileWork.execute(() -> {
            IOException failure = null;
            try {
                files.trash(target);
            } catch (IOException e) {
                failure = e;
            }
            IOException problem = failure;
            Platform.runLater(() -> {
                trashing = false;
                if (problem == null) {
                    controller.trashed(target);
                } else {
                    showProblem("Move to Trash", "Can't move " + target.getFileName() + " to the Trash:\n"
                            + problem.getMessage() + "\n\nNothing was deleted.");
                }
            });
        });
    }

    private void showProblem(String title, String text) {
        Alert error = new Alert(Alert.AlertType.ERROR, text);
        error.initOwner(stage);
        error.setTitle(title);
        error.setHeaderText(null);
        error.showAndWait();
    }

    /** "Save rotation?" (#51): Enter saves, Esc doesn't; "Don't ask again" makes the choice the setting. */
    @Override
    public ViewerController.RotationAnswer askSaveRotation(String name) {
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        ButtonType dontSave = new ButtonType("Don't save", ButtonBar.ButtonData.CANCEL_CLOSE);
        CheckBox dontAsk = new CheckBox("Don't ask again");
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION, null, save, dontSave);
        ask.initOwner(stage);
        ask.setTitle("Save rotation");
        ask.setHeaderText(null);
        ask.getDialogPane().setContent(new VBox(12, new Label("Save the rotation of " + name + " to the file?"),
                dontAsk));
        boolean saving = ask.showAndWait().orElse(dontSave) == save;
        return new ViewerController.RotationAnswer(saving, dontAsk.isSelected());
    }

    @Override
    public void showRotationNotSavable(String name) {
        Alert note = new Alert(Alert.AlertType.INFORMATION, "The rotation of " + name + " stays in the view: "
                + "linview saves rotations to JPEG, PNG and BMP files only.");
        note.initOwner(stage);
        note.setTitle("Save rotation");
        note.setHeaderText(null);
        note.showAndWait();
    }

    /** Writes the turn on the file thread; stored thumbnails of the file are deleted so they are made again. */
    @Override
    public void saveRotation(Path file, Object stamp, Orientation stored, double width, double height,
                             int quarterTurns) {
        fileWork.execute(() -> {
            IOException failure = null;
            try {
                files.saveRotation(file, stamp, stored, width, height, quarterTurns);
            } catch (IOException e) {
                failure = e;
            }
            if (failure == null) {
                try {
                    thumbnails.forget(file);
                } catch (IOException e) {
                    System.getLogger("linview").log(System.Logger.Level.WARNING,
                            "Can't delete the old thumbnail of " + file + ": " + e.getMessage());
                }
            }
            IOException problem = failure;
            Platform.runLater(() -> {
                if (problem == null) {
                    strip.refresh();   // visible thumbnails are asked for again: the saved one is made anew
                    controller.rotationSaved(file, quarterTurns);
                } else {
                    showProblem("Save rotation", "Can't save the rotation of " + file.getFileName() + ":\n"
                            + problem.getMessage() + "\n\nThe file was not changed.");
                }
            });
        });
    }

    /** Ctrl+O / O: pick an image or a folder; the image's folder becomes the list. */
    private void chooseAndOpen() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open image");
        List<String> patterns = new ArrayList<>();
        for (String ext : ImageList.EXTENSIONS) {
            patterns.add("*." + ext);
            patterns.add("*." + ext.toUpperCase(Locale.ROOT));
        }
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images", patterns));
        chooser.setInitialDirectory(controller.startFolder().toFile());

        // Folders too, without a DirectoryChooser: in this (GTK) dialog a folder can be chosen and opened as well as
        // an image. Checked by Marcos on 2026-10-23 (#50, closed without code; review finding R8 withdrawn), so no
        // separate folder dialog or Ctrl+Shift+O is needed. Raised again in a review on 2026-10-25: still settled.
        File chosen = chooser.showOpenDialog(stage);
        if (chosen != null) {
            controller.open(chosen.toPath());
        } else {
            controller.showOpenHintIfEmpty();
        }
    }

    @Override
    public void showImage(Loaded<Image> image, Zoom.View view) {
        viewer.show(image, view);
    }

    @Override
    public void showFullImage(Image image) {
        viewer.showFullImage(image);
    }

    @Override
    public void showMessage(String text) {
        viewer.showMessage(text);
    }

    @Override
    public void showTitle(String title) {
        stage.setTitle(title);
    }

    @Override
    public void showZoom(double width, double height, double percent, boolean locked) {
        taskbar.show(width, height, percent, locked);
    }

    /** Full-screen only, with something to say, and not while the taskbar is open (#45). */
    private void updateOverlay() {
        overlay.setVisible(stage.isFullScreen() && !overlay.getText().isEmpty() && !taskbarOpen);
    }

    @Override
    public void showPosition(List<Path> files, int index) {
        shownFiles = files;
        shownIndex = index;
        strip.show(files, index);
        overlay.setText(index < 0 ? "" : ViewerController.overlayText(files.get(index).getFileName().toString(),
                index + 1, files.size(), settings.counterOverlay(), settings.fileNameOverlay()));
        updateOverlay();
    }
}
