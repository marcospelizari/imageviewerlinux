package linview.ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import java.nio.file.Path;

import javafx.beans.InvalidationListener;
import javafx.geometry.Dimension2D;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import linview.image.Loaded;
import linview.image.ThumbnailLoader;
import linview.settings.Settings;
import linview.viewer.ImageList;
import linview.viewer.ViewerController;
import linview.viewer.Zoom;

/**
 * The window (#53): Stage and Scene, the Open dialog and full-screen, and what the controller shows. Every action goes
 * through {@link #execute}: keys call it now, the taskbar, panels and menu later (REPORT.md §4.2). What an action does
 * to the viewing stays in {@link ViewerController}; this class only adds what belongs to the window.
 */
public final class ViewerWindow implements ViewerController.Display<Image> {

    /** Icon sizes in src/main/resources/linview/icons; the desktop picks the best one for each place. */
    static final int[] ICON_SIZES = {16, 32, 48, 64, 128, 256, 512};

    private final Stage stage;
    private final ViewerPane viewer = new ViewerPane();
    private final ThumbStrip strip;
    private ViewerController<Image> controller;

    /** {@code thumbnails} fills the full-screen thumbnail strip (#28). */
    public ViewerWindow(Stage stage, ThumbnailLoader thumbnails) {
        this.stage = stage;
        strip = new ThumbStrip(thumbnails, index -> controller.select(index));
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

        EdgePanels panels = new EdgePanels(stage, viewer);
        panels.setTop(strip, strip::scrollToCurrent);
        Dimension2D content = WindowPlacement.placeAtStart(stage, settings.windowX(), settings.windowY(),
                settings.windowWidth(), settings.windowHeight());
        Scene scene = new Scene(new StackPane(viewer, panels.layer()), content.getWidth(), content.getHeight());
        scene.getStylesheets().add(ViewerWindow.class.getResource("/linview/viewer.css").toExternalForm());
        panels.watch(scene);
        scene.setOnKeyPressed(this::keyPressed);
        scene.setOnMouseClicked(e -> {
            // A drag that ends where it started is not a click (R-ZOOM-05).
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && e.isStillSincePress()) {
                execute(Keys.Action.FULL_SCREEN);
            }
        });

        stage.setFullScreenExitHint("");
        stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);   // Esc quits, also from full-screen (#26)
        stage.setTitle(ViewerController.TITLE);
        for (int size : ICON_SIZES) {
            stage.getIcons().add(new Image(ViewerWindow.class.getResource(iconPath(size)).toExternalForm()));
        }
        stage.setScene(scene);
        stage.show();
        rememberWindow(scene);
        stage.setOnHiding(e -> controller.windowClosed(stage.isFullScreen(), windowX, windowY, windowWidth,
                windowHeight));
        if (settings.fullScreen()) {
            stage.setFullScreen(true);   // on the monitor the window just opened on
        }
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
            case OPEN -> chooseAndOpen();
            case FULL_SCREEN -> stage.setFullScreen(!stage.isFullScreen());   // Enter, F11, double-click (R-VIEW-02)
            case ZOOM_IN -> controller.zoomIn();
            case ZOOM_OUT -> controller.zoomOut();
            case FIT -> controller.setZoomMode(Zoom.Mode.FIT);
            case FIT_WIDTH -> controller.setZoomMode(Zoom.Mode.WIDTH);
            case ACTUAL_SIZE -> controller.setZoomMode(Zoom.Mode.ACTUAL);
            case FIT_OR_ACTUAL -> controller.toggleFitOrActual();
            case ZOOM_LOCK -> controller.toggleZoomLock();
            case ROTATE_RIGHT -> controller.rotateRight();
            case ROTATE_LEFT -> controller.rotateLeft();
            // Quits, also from full-screen, which the next start remembers (Marcos, 2026-10-24, #26).
            case ESCAPE -> stage.close();
        }
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
    public void showMessage(String text) {
        viewer.showMessage(text);
    }

    @Override
    public void showTitle(String title) {
        stage.setTitle(title);
    }

    @Override
    public void showPosition(List<Path> files, int index) {
        strip.show(files, index);
    }
}
