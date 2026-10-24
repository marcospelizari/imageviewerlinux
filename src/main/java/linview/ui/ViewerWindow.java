package linview.ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import linview.image.Loaded;
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
    private ViewerController<Image> controller;

    public ViewerWindow(Stage stage) {
        this.stage = stage;
    }

    static String iconPath(int size) {
        return "/linview/icons/linview-" + size + ".png";
    }

    /**
     * Builds the scene for {@code controller}, which is shown to, with the background colour of {@code settings}, and
     * opens the window on the mouse's monitor.
     */
    public void show(ViewerController<Image> controller, Settings settings) {
        this.controller = controller;
        viewer.connect(controller, stage);
        viewer.setBackgroundColour(settings.backgroundColour());

        Scene scene = new Scene(viewer, 1024, 700);
        scene.setOnKeyPressed(this::keyPressed);
        scene.setOnMouseClicked(e -> {
            // A drag that ends where it started is not a click (R-ZOOM-05).
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && e.isStillSincePress()) {
                execute(Keys.Action.FULL_SCREEN);
            }
        });

        stage.setFullScreenExitHint("");
        stage.setTitle(ViewerController.TITLE);
        for (int size : ICON_SIZES) {
            stage.getIcons().add(new Image(ViewerWindow.class.getResource(iconPath(size)).toExternalForm()));
        }
        stage.setScene(scene);
        WindowPlacement.placeOnMouseScreen(stage, scene.getWidth(), scene.getHeight());
        stage.show();
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
            case ESCAPE -> {
                // In full-screen JavaFX itself leaves full-screen on Esc.
                if (!stage.isFullScreen()) {
                    stage.close();
                }
            }
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
}
