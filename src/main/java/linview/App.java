package linview;

import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javafx.application.Application;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.robot.Robot;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * Entry point: opens the file or folder given on the command line (or with Ctrl+O) and shows its image (R-NAV-01).
 * Keys are in {@link Keys}.
 */
public class App extends Application {

    static final String TITLE = "linview";

    static final String OPEN_HINT = "Press Ctrl+O to open an image or folder";

    /** Icon sizes in src/main/resources/linview/icons; the desktop picks the best one for each place. */
    static final int[] ICON_SIZES = {16, 32, 48, 64, 128, 256, 512};

    static String iconPath(int size) {
        return "/linview/icons/linview-" + size + ".png";
    }

    /** {@code name — n/N — W×H — linview}; the size is left out while it isn't known yet ({@code width <= 0}). */
    static String title(String name, int position, int size, double width, double height) {
        String dimensions = width > 0 ? String.format(" \u2014 %.0f\u00d7%.0f", width, height) : "";
        return name + " \u2014 " + position + "/" + size + dimensions + " \u2014 " + TITLE;
    }

    private static final System.Logger LOG = System.getLogger("linview");

    private final ViewerPane viewer = new ViewerPane();
    /** Loaded at start (R-SET-01); each feature that changes a setting saves it with {@link Settings#save}. */
    private Settings settings;
    private Stage stage;
    /** The open folder; {@code null} when nothing is open. */
    private ImageList images;
    /** The image being loaded or shown; a late result from an earlier one is ignored. */
    private Image current;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        settings = Settings.load(Settings.defaultFile(), warning -> LOG.log(System.Logger.Level.WARNING, warning));
        Scene scene = new Scene(viewer, 1024, 700);
        scene.setOnKeyPressed(this::keyPressed);
        scene.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                toggleFullScreen();
            }
        });

        stage.setFullScreenExitHint("");
        stage.setTitle(TITLE);
        for (int size : ICON_SIZES) {
            stage.getIcons().add(new Image(App.class.getResource(iconPath(size)).toExternalForm()));
        }
        stage.setScene(scene);
        placeOnMouseScreen(stage, scene.getWidth(), scene.getHeight());
        stage.show();

        List<String> args = getParameters().getRaw();
        if (args.isEmpty()) {
            viewer.showMessage(OPEN_HINT);
        } else {
            open(Path.of(args.get(0)));
        }
    }

    private void keyPressed(KeyEvent e) {
        Keys.Action action = Keys.actionFor(e);
        if (action == null) {
            return;
        }
        switch (action) {
            case NEXT -> moveTo(images == null ? null : images.next());
            case PREVIOUS -> moveTo(images == null ? null : images.previous());
            case FIRST -> moveTo(images == null ? null : images.first());
            case LAST -> moveTo(images == null ? null : images.last());
            case OPEN -> chooseAndOpen();
            case FULL_SCREEN -> toggleFullScreen();
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

    /** Shows {@code moved} unless it's the image already shown (end of the folder, or nothing open). */
    private void moveTo(ImageList moved) {
        if (moved != null && moved != images) {
            images = moved;
            showCurrent();
        }
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
        chooser.setInitialDirectory(startFolder().toFile());

        File chosen = chooser.showOpenDialog(stage);
        if (chosen != null) {
            open(chosen.toPath());
        } else if (images == null || images.isEmpty()) {
            viewer.showMessage(OPEN_HINT);
        }
    }

    private Path startFolder() {
        if (images != null && Files.isDirectory(images.folder())) {
            return images.folder();
        }
        Path home = Path.of(System.getProperty("user.home"));
        Path pictures = home.resolve("Pictures");
        return Files.isDirectory(pictures) ? pictures : home;
    }

    private void open(Path path) {
        try {
            images = ImageList.open(path);
        } catch (NoSuchFileException e) {
            nothingOpen("Not found: " + path);
            return;
        } catch (AccessDeniedException e) {
            nothingOpen("No permission to read: " + path);
            return;
        } catch (IOException e) {
            nothingOpen(e.getMessage());
            return;
        }
        if (images.isEmpty()) {
            nothingOpen("No images in " + images.folder() + "\n\n" + OPEN_HINT);
            return;
        }
        showCurrent();
    }

    private void nothingOpen(String message) {
        images = null;
        current = null;
        stage.setTitle(TITLE);
        viewer.showMessage(message);
    }

    private void showCurrent() {
        Path file = images.current();
        String name = file.getFileName().toString();
        stage.setTitle(title(name, images.position(), images.size(), 0, 0));

        Orientation orientation = Orientation.read(file);
        // Background loading keeps the window responsive; the result arrives through the listeners.
        Image previous = current;
        Image image = new Image(file.toUri().toString(), true);
        current = image;
        if (previous != null) {
            previous.cancel();   // stop decoding an image no longer wanted (fast key presses); set current first so it's ignored
        }
        image.progressProperty().addListener((obs, old, now) -> loaded(image, name, orientation));
        image.errorProperty().addListener((obs, old, now) -> loaded(image, name, orientation));
        loaded(image, name, orientation);
    }

    private void loaded(Image image, String name, Orientation orientation) {
        if (image != current) {
            return;
        }
        if (image.isError()) {
            viewer.showMessage("Can't show " + name + ": the file is damaged or not a supported image");
        } else if (image.getProgress() >= 1 && image.getWidth() > 0) {
            boolean swap = orientation.swapsSides();
            double width = swap ? image.getHeight() : image.getWidth();
            double height = swap ? image.getWidth() : image.getHeight();
            stage.setTitle(title(name, images.position(), images.size(), width, height));
            viewer.show(image, orientation);
        }
    }

    /** Without an explicit position JavaFX centres on the primary monitor; use the mouse's monitor instead. */
    private static void placeOnMouseScreen(Stage stage, double width, double height) {
        List<Rectangle2D> screens = Screen.getScreens().stream().map(Screen::getVisualBounds).toList();
        Rectangle2D screen = WindowPlacement.screenAt(
                new Robot().getMousePosition(), screens, Screen.getPrimary().getVisualBounds());
        Point2D corner = WindowPlacement.centredOn(screen, width, height);
        stage.setX(corner.getX());
        stage.setY(corner.getY());
    }

    public static void main(String[] args) {
        launch(args);
    }
}
