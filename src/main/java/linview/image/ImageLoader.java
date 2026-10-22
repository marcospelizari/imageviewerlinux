package linview.image;

import java.nio.file.Path;
import java.util.function.Consumer;

import javafx.scene.image.Image;

/**
 * Loads images in the background with JavaFX, which keeps the window responsive. Phase 3 adds screen-size
 * loading, preloading and the cache here (R-PERF-01–03); #21 moves the EXIF read off the UI thread.
 */
public final class ImageLoader {

    /**
     * Starts loading {@code file}; {@code done} is called once, on the JavaFX thread, when the image is ready or
     * has failed.
     *
     * @return cancels the load, e.g. when another image was asked for (fast key presses)
     */
    public Runnable load(Path file, Consumer<Loaded<Image>> done) {
        Orientation orientation = Orientation.read(file);
        Image image = new Image(file.toUri().toString(), true);
        boolean[] reported = {false};
        Runnable check = () -> {
            if (reported[0]) {
                return;
            }
            if (image.isError()) {
                reported[0] = true;
                done.accept(Loaded.failed());
            } else if (image.getProgress() >= 1 && image.getWidth() > 0) {
                reported[0] = true;
                done.accept(new Loaded<>(image, image.getWidth(), image.getHeight(), orientation));
            }
        };
        image.progressProperty().addListener((obs, old, now) -> check.run());
        image.errorProperty().addListener((obs, old, now) -> check.run());
        check.run();
        return image::cancel;
    }
}
