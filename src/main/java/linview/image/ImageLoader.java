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
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

/**
 * Loads images in the background, which keeps the window responsive. The EXIF orientation and the file's real size
 * are read on a worker thread (#19). A very large image is decoded only as large as the screen and the zoom need
 * (#19, {@link #SHRINK_ABOVE_PIXELS}); the full image when the zoom asks for it (#22). JPEG, PNG, GIF and BMP use JavaFX's own loader (fastest, and
 * animated GIFs play); WebP, TIFF, PSD and TGA use the ImageIO readers from TwelveMonkeys (R-FMT-02). Phase 3 adds
 * preloading and the cache here (R-PERF-01, R-PERF-03).
 */
public final class ImageLoader {

    /** Extensions decoded with ImageIO; everything else goes to JavaFX. */
    static final Set<String> IMAGEIO_EXTENSIONS = Set.of("webp", "tif", "tiff", "psd", "tga");

    /**
     * Images up to this many pixels load whole: shrinking while loading costs JavaFX more CPU time than the graphics
     * card needs to show the full image (7.2 MP: 285 ms whole, 515 ms shrunk; break-even near 40 MP, measured
     * 2026-10-24, REPORT.md §5.2). Larger images are shrunk, which is faster and keeps memory low.
     */
    static final double SHRINK_ABOVE_PIXELS = 40e6;

    private final double screenWidth;
    private final double screenHeight;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "linview-decoder");
        thread.setDaemon(true);
        return thread;
    });

    /** {@code screenWidth} × {@code screenHeight}: the largest screen, in physical pixels. */
    public ImageLoader(double screenWidth, double screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        // ImageIO finds its readers on first use (≈ 60 ms); do it while the window opens, not for the first image.
        worker.execute(() -> ImageIO.getImageReadersBySuffix("jpg"));
    }

    /**
     * Starts loading {@code file}, large enough to cover the screen and with at least {@code minResolution} decoded
     * pixels per real pixel (1 = the full image); {@code done} is called once, on the JavaFX thread, when the image
     * is ready or has failed. The result carries the time from this call until the image was ready (#23).
     *
     * @return cancels the load, e.g. when another image was asked for (fast key presses)
     */
    public Runnable load(Path file, double minResolution, Consumer<Loaded<Image>> done) {
        long start = System.nanoTime();
        String extension = extension(file);
        AtomicBoolean cancelled = new AtomicBoolean();
        Runnable[] cancelJavaFX = {() -> { }};   // only touched on the JavaFX thread
        Future<?> task = worker.submit(() -> {
            Orientation orientation = Orientation.read(file);
            if (IMAGEIO_EXTENSIONS.contains(extension)) {
                Loaded<Image> result = decode(file, extension, orientation, minResolution).timed(millisSince(start));
                Platform.runLater(() -> {
                    if (!cancelled.get()) {
                        done.accept(result);
                    }
                });
            } else {
                double[] size = storedSize(file, extension);
                double scale = size == null ? 1 : decodeScale(size[0], size[1], orientation, minResolution);
                Platform.runLater(() -> {
                    if (!cancelled.get()) {
                        cancelJavaFX[0] = loadWithJavaFX(file, size, scale, orientation, start, done);
                    }
                });
            }
        });
        return () -> {
            cancelled.set(true);
            task.cancel(true);   // a load not started yet never runs; a running ImageIO decode is interrupted
            cancelJavaFX[0].run();
        };
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

    /**
     * How far to shrink an image of {@code width} × {@code height} (as stored) for the screen: just enough that, turned
     * upright, it still covers the screen in both directions, so fit, fit width and fit height need no other copy.
     * Never enlarges: 1 for an image the screen can show whole.
     */
    static double screenScale(double width, double height, Orientation orientation,
                              double screenWidth, double screenHeight) {
        double uprightWidth = orientation.swapsSides() ? height : width;
        double uprightHeight = orientation.swapsSides() ? width : height;
        return Math.min(1, Math.max(screenWidth / uprightWidth, screenHeight / uprightHeight));
    }

    private double decodeScale(double width, double height, Orientation orientation, double minResolution) {
        return decodeScale(width, height, orientation, minResolution, screenWidth, screenHeight);
    }

    /** 1 up to {@link #SHRINK_ABOVE_PIXELS}; above, the screen's scale raised to {@code minResolution}, at most 1. */
    static double decodeScale(double width, double height, Orientation orientation, double minResolution,
                              double screenWidth, double screenHeight) {
        if (width * height <= SHRINK_ABOVE_PIXELS) {
            return 1;
        }
        double screen = screenScale(width, height, orientation, screenWidth, screenHeight);
        return Math.min(1, Math.max(screen, minResolution));
    }

    /** ImageIO keeps every {@code n}-th pixel: the largest {@code n} whose copy is still at least {@code scale}. */
    static int subsampling(double scale) {
        return Math.max(1, (int) Math.floor(1 / scale + 1e-9));
    }

    private static double millisSince(long start) {
        return (System.nanoTime() - start) / 1e6;
    }

    /** The size in the file's header, without decoding; {@code null} when it can't be read (a damaged file). */
    private static double[] storedSize(Path file, String extension) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersBySuffix(extension);
        if (!readers.hasNext()) {
            return null;
        }
        ImageReader reader = readers.next();
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) {
                return null;
            }
            reader.setInput(in, true, true);
            return new double[] {reader.getWidth(0), reader.getHeight(0)};
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            reader.dispose();
        }
    }

    /**
     * On the JavaFX thread. With {@code scale} below 1 JavaFX decodes straight to the smaller size. Without a known
     * size (damaged header) the image loads whole, as before, and JavaFX shows what it can.
     */
    private static Runnable loadWithJavaFX(Path file, double[] size, double scale, Orientation orientation,
                                           long start, Consumer<Loaded<Image>> done) {
        String url = file.toUri().toString();
        Image image = scale < 1
                ? new Image(url, Math.ceil(size[0] * scale), Math.ceil(size[1] * scale), true, true, true)
                : new Image(url, true);
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
                double width = size == null ? image.getWidth() : size[0];
                double height = size == null ? image.getHeight() : size[1];
                double resolution = Math.min(1, image.getWidth() / width);
                done.accept(new Loaded<>(image, width, height, orientation, resolution, millisSince(start)));
            }
        };
        image.progressProperty().addListener((obs, old, now) -> check.run());
        image.errorProperty().addListener((obs, old, now) -> check.run());
        check.run();
        return image::cancel;
    }

    /**
     * The first image in the file (page 1 of a TIFF, frame 1 of an animated WebP), every n-th pixel when that still
     * covers the screen and {@code minResolution}. The reader is chosen by extension, since TGA has no signature to
     * recognise it by.
     */
    private Loaded<Image> decode(Path file, String extension, Orientation orientation, double minResolution) {
        try {
            Iterator<ImageReader> readers = ImageIO.getImageReadersBySuffix(extension);
            if (!readers.hasNext()) {
                return Loaded.failed();
            }
            ImageReader reader = readers.next();
            BufferedImage decoded;
            double width;
            double height;
            try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
                if (in == null) {
                    return Loaded.failed();
                }
                reader.setInput(in);
                width = reader.getWidth(0);
                height = reader.getHeight(0);
                ImageReadParam param = reader.getDefaultReadParam();
                int step = subsampling(decodeScale(width, height, orientation, minResolution));
                if (step > 1) {
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                decoded = reader.read(0, param);
            } finally {
                reader.dispose();
            }
            double resolution = Math.min(1, decoded.getWidth() / width);
            return new Loaded<>(toFxImage(decoded), width, height, orientation, resolution, 0);
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
