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
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.robot.Robot;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * Entry point: opens the file or folder given on the command line (or with Ctrl+O) and shows its image (R-NAV-01).
 * Enter toggles full-screen, Esc quits.
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

    private final ViewerPane viewer = new ViewerPane();
    private Stage stage;
    /** The open folder; {@code null} when nothing is open. */
    private ImageList images;
    /** The image being loaded or shown; a late result from an earlier one is ignored. */
    private Image current;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Scene scene = new Scene(viewer, 1024, 700);
        scene.setOnKeyPressed(this::keyPressed);

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
        if (e.getCode() == KeyCode.ENTER) {
            stage.setFullScreen(!stage.isFullScreen());
        } else if (e.getCode() == KeyCode.ESCAPE && !stage.isFullScreen()) {
            stage.close();
        } else if (e.getCode() == KeyCode.O && !e.isAltDown() && !e.isShiftDown()) {
            chooseAndOpen();
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

        // Background loading keeps the window responsive; the result arrives through the listeners.
        Image image = new Image(file.toUri().toString(), true);
        current = image;
        image.progressProperty().addListener((obs, old, now) -> loaded(image, name));
        image.errorProperty().addListener((obs, old, now) -> loaded(image, name));
        loaded(image, name);
    }

    private void loaded(Image image, String name) {
        if (image != current) {
            return;
        }
        if (image.isError()) {
            viewer.showMessage("Can't show " + name + ": the file is damaged or not a supported image");
        } else if (image.getProgress() >= 1 && image.getWidth() > 0) {
            stage.setTitle(title(name, images.position(), images.size(), image.getWidth(), image.getHeight()));
            viewer.show(image);
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
