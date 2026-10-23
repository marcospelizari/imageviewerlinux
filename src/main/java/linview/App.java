package linview;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javafx.application.Application;
import javafx.beans.InvalidationListener;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import linview.image.ImageLoader;
import linview.image.Orientation;
import linview.settings.Settings;
import linview.settings.SettingsStore;
import linview.ui.Keys;
import linview.ui.ViewerPane;
import linview.ui.WindowPlacement;
import linview.viewer.ImageList;
import linview.viewer.ViewerController;
import linview.viewer.Zoom;

/**
 * Entry point and composition root (REPORT.md §6): builds the components, connects them and owns the window.
 * Opens the file or folder given on the command line (R-NAV-01). Keys are in {@link Keys}; what they do is in
 * {@link ViewerController}.
 */
public class App extends Application {

    /** Icon sizes in src/main/resources/linview/icons; the desktop picks the best one for each place. */
    static final int[] ICON_SIZES = {16, 32, 48, 64, 128, 256, 512};

    static String iconPath(int size) {
        return "/linview/icons/linview-" + size + ".png";
    }

    private static final System.Logger LOG = System.getLogger("linview");

    private Stage stage;
    private ViewerController<Image> controller;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        SettingsStore store = new SettingsStore(SettingsStore.defaultFile(),
                warning -> LOG.log(System.Logger.Level.WARNING, warning));
        Settings settings = store.load();
        ViewerPane viewer = new ViewerPane();
        controller = new ViewerController<>(settings, store, new ImageLoader()::load, new ViewerController.Display<>() {
            @Override
            public void showImage(Image image, Orientation orientation, Zoom.View view) {
                viewer.show(image, orientation, view);
            }

            @Override
            public void showMessage(String text) {
                viewer.showMessage(text);
            }

            @Override
            public void showTitle(String title) {
                stage.setTitle(title);
            }
        });
        viewer.sendGesturesTo(controller);
        InvalidationListener resized = obs -> controller.resize(viewer.getWidth(), viewer.getHeight());
        viewer.widthProperty().addListener(resized);
        viewer.heightProperty().addListener(resized);

        Scene scene = new Scene(viewer, 1024, 700);
        scene.setOnKeyPressed(this::keyPressed);
        scene.setOnMouseClicked(e -> {
            // A drag that ends where it started is not a click (R-ZOOM-05).
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && e.isStillSincePress()) {
                toggleFullScreen();
            }
        });

        stage.setFullScreenExitHint("");
        stage.setTitle(ViewerController.TITLE);
        for (int size : ICON_SIZES) {
            stage.getIcons().add(new Image(App.class.getResource(iconPath(size)).toExternalForm()));
        }
        stage.setScene(scene);
        WindowPlacement.placeOnMouseScreen(stage, scene.getWidth(), scene.getHeight());
        stage.show();

        List<String> args = getParameters().getRaw();
        if (args.isEmpty()) {
            controller.showOpenHintIfEmpty();
        } else {
            controller.open(Path.of(args.get(0)));
        }
    }

    private void keyPressed(KeyEvent e) {
        Keys.Action action = Keys.actionFor(e);
        if (action == null) {
            return;
        }
        switch (action) {
            case NEXT -> controller.next();
            case PREVIOUS -> controller.previous();
            case FIRST -> controller.first();
            case LAST -> controller.last();
            case OPEN -> chooseAndOpen();
            case FULL_SCREEN -> toggleFullScreen();
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

    /** Enter, F11 or a double-click (R-VIEW-02). */
    private void toggleFullScreen() {
        stage.setFullScreen(!stage.isFullScreen());
    }

    /** Ctrl+O / O: pick an image; its folder becomes the list. */
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

    public static void main(String[] args) {
        launch(args);
    }
}
