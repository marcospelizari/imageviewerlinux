package linview.image;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

/**
 * Loads images in the background, which keeps the window responsive. JPEG, PNG, GIF and BMP use JavaFX's own
 * loader (fastest, and animated GIFs play); WebP, TIFF, PSD and TGA use the ImageIO readers from TwelveMonkeys
 * (R-FMT-02) on one worker thread. Phase 3 adds screen-size loading, preloading and the cache here
 * (R-PERF-01–03); #21 moves the EXIF read off the UI thread.
 */
public final class ImageLoader {

    /** Extensions decoded with ImageIO; everything else goes to JavaFX. */
    static final Set<String> IMAGEIO_EXTENSIONS = Set.of("webp", "tif", "tiff", "psd", "tga");

    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "linview-decoder");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Starts loading {@code file}; {@code done} is called once, on the JavaFX thread, when the image is ready or
     * has failed.
     *
     * @return cancels the load, e.g. when another image was asked for (fast key presses)
     */
    public Runnable load(Path file, Consumer<Loaded<Image>> done) {
        Orientation orientation = Orientation.read(file);
        String extension = extension(file);
        return IMAGEIO_EXTENSIONS.contains(extension)
                ? loadWithImageIO(file, extension, orientation, done)
                : loadWithJavaFX(file, orientation, done);
    }

    /** Stops the worker thread: a decode still running is interrupted. Called when the app closes. */
    public void close() {
        worker.shutdownNow();
    }

    static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Runnable loadWithJavaFX(Path file, Orientation orientation, Consumer<Loaded<Image>> done) {
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

    private Runnable loadWithImageIO(Path file, String extension, Orientation orientation,
                                     Consumer<Loaded<Image>> done) {
        AtomicBoolean cancelled = new AtomicBoolean();
        Future<?> task = worker.submit(() -> {
            Loaded<Image> result = decode(file, extension, orientation);
            Platform.runLater(() -> {
                if (!cancelled.get()) {
                    done.accept(result);
                }
            });
        });
        return () -> {
            cancelled.set(true);
            task.cancel(true);   // a decode not started yet never runs
        };
    }

    /**
     * The first image in the file (page 1 of a TIFF, frame 1 of an animated WebP). The reader is chosen by
     * extension, since TGA has no signature to recognise it by.
     */
    private static Loaded<Image> decode(Path file, String extension, Orientation orientation) {
        try {
            Iterator<ImageReader> readers = ImageIO.getImageReadersBySuffix(extension);
            if (!readers.hasNext()) {
                return Loaded.failed();
            }
            ImageReader reader = readers.next();
            BufferedImage decoded;
            try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
                if (in == null) {
                    return Loaded.failed();
                }
                reader.setInput(in);
                decoded = reader.read(0);
            } finally {
                reader.dispose();
            }
            return new Loaded<>(toFxImage(decoded), decoded.getWidth(), decoded.getHeight(), orientation);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            // Damaged, unsupported or too large: a message, never a hang. The large arrays are gone by now,
            // so an out-of-memory here is recoverable.
            return Loaded.failed();
        }
    }

    /** ARGB pixels, converted from any colour model (greyscale, CMYK, 16-bit) by {@code getRGB}. */
    private static WritableImage toFxImage(BufferedImage decoded) {
        int width = decoded.getWidth();
        int height = decoded.getHeight();
        int[] argb = decoded.getRGB(0, 0, width, height, null, 0, width);
        WritableImage image = new WritableImage(width, height);
        image.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), argb, 0, width);
        return image;
    }
}
