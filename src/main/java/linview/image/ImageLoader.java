package linview.image;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
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
 * (#19, {@link #SHRINK_ABOVE_PIXELS}); the full image when the zoom asks for it (#22). JPEG, PNG, GIF and BMP use
 * JavaFX's own loader (fastest, and animated GIFs play); WebP, TIFF, PSD and TGA use the ImageIO readers from
 * TwelveMonkeys (R-FMT-02).
 *
 * <p>Preloading (#21): {@link #preload} names the images likely to come next; they are loaded one at a time on their
 * own thread and kept, with the current image, in a small window; anything else is dropped. The selected image never
 * waits for a preload: none starts while it loads, and one running goes on beside it on its own thread; it is
 * cancelled only when no longer wanted. A kept image is used only while its file still has the same modified time and
 * size (R4), checked on the worker thread. Preloads ask for what the zoom mode needs, but never more than 40 MP
 * decoded, so huge neighbours stay bounded (#55). Kept images are a cache of {@link #CACHE_BUDGET_BYTES} (#20).
 *
 * <p>Decode memory (#20): what a decode needs is estimated from the header before it starts ({@link #decodeBytes}).
 * Above {@link #PRELOAD_DECODE_LIMIT} an image is not preloaded and its decode runs on a thread of its own, one at a
 * time: JavaFX can't stop a decode it has started, so this is what bounds them, and normal images never wait behind
 * one. Above {@link #DECODE_LIMIT} it is refused as {@link Loaded#tooLarge too large}, and so is a decode that runs
 * out of memory anyway. All public methods are called on the JavaFX thread.
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

    /** How much decoded image the cache keeps, W×H×4 (decided 2026-10-24, REPORT.md §5.2). */
    static final long CACHE_BUDGET_BYTES = 300L << 20;

    /** A decode needing more than this (≈ 100 MP JPEG) is not preloaded and runs one at a time (Marcos, #20). */
    static final long PRELOAD_DECODE_LIMIT = 300L << 20;

    /**
     * A decode needing more than this is refused: the 2 GB heap ({@code -Xmx2g}) less the cache and a preload
     * (Marcos, #20).
     */
    static final long DECODE_LIMIT = 1200L << 20;

    /** The ImageIO path briefly holds the decoded image, its ARGB array and the JavaFX image (REPORT.md §6.8). */
    private static final int IMAGEIO_BYTES_PER_PIXEL = 12;

    private static final System.Logger LOG = System.getLogger("linview");

    /** A file's modified time and size; {@code null} from {@link #of} when it can't be read (e.g. deleted). */
    private record Stamp(FileTime modified, long size) {

        static Stamp of(Path file) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                return new Stamp(attributes.lastModifiedTime(), attributes.size());
            } catch (IOException e) {
                return null;
            }
        }
    }

    private final double screenWidth;
    private final double screenHeight;
    /** Decodes the selected image; preloads have their own thread, so they never queue in front of it. */
    private final ExecutorService worker = daemonThread("linview-decoder");
    private final ExecutorService preloader = daemonThread("linview-preloader");
    /** Decodes above {@link #PRELOAD_DECODE_LIMIT}, one at a time. */
    private final ExecutorService large = daemonThread("linview-large-decoder");

    /** Which image loads when, and the window of kept images (#21). */
    private final LoadScheduler<Image> scheduler = new LoadScheduler<>(new LoadScheduler.Decoder<>() {
        @Override
        public Runnable decode(Path file, double minResolution, boolean preload,
                               BiConsumer<Loaded<Image>, Object> done) {
            return decodeOn(preload ? preloader : worker, file, minResolution, preload, done);
        }

        @Override
        public Runnable stamp(Path file, Consumer<Object> done) {
            return stampOn(worker, file, done);
        }
    }, CACHE_BUDGET_BYTES);

    /** {@code screenWidth} × {@code screenHeight}: the largest screen, in physical pixels. */
    public ImageLoader(double screenWidth, double screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        // ImageIO finds its readers on first use (≈ 60 ms); do it while the window opens, not for the first image.
        worker.execute(() -> ImageIO.getImageReadersBySuffix("jpg"));
    }

    private static ExecutorService daemonThread(String name) {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts loading {@code file}, the selected image, large enough to cover the screen and with at least
     * {@code minResolution} decoded pixels per real pixel (1 = the full image); {@code done} is called once, on the
     * JavaFX thread, when the image is ready or has failed; for a kept image (load time 0) once a check on the worker
     * thread finds its file unchanged. The result carries
     * the time from this call until the image was ready (#23).
     *
     * @return cancels the load, e.g. when another image was asked for (fast key presses)
     */
    public Runnable load(Path file, double minResolution, Consumer<Loaded<Image>> done) {
        return scheduler.load(file, minResolution, done);
    }

    /**
     * The images likely to come next, most wanted first (#21), with what the zoom mode needs but at most 40 MP decoded
     * (#55); see {@link LoadScheduler}.
     */
    public void preload(List<Path> files, double minResolution) {
        scheduler.preload(files, minResolution);
    }

    /** The file's state, read on {@code lane} (a USB disk or network mount can be slow), answered on JavaFX's. */
    private static Runnable stampOn(ExecutorService lane, Path file, Consumer<Object> done) {
        AtomicBoolean cancelled = new AtomicBoolean();
        Future<?> task = lane.submit(() -> {
            Stamp stamp = Stamp.of(file);
            Platform.runLater(() -> {
                if (!cancelled.get()) {
                    done.accept(stamp);
                }
            });
        });
        return () -> {
            cancelled.set(true);
            task.cancel(false);
        };
    }

    /**
     * Decodes {@code file} on {@code lane}; {@code done} gets the result with the file's state when loading began,
     * on the JavaFX thread, unless cancelled first.
     */
    private Runnable decodeOn(ExecutorService lane, Path file, double minResolution, boolean preload,
                              BiConsumer<Loaded<Image>, Object> done) {
        long start = System.nanoTime();
        String extension = extension(file);
        AtomicBoolean cancelled = new AtomicBoolean();
        Runnable[] cancelJavaFX = {() -> { }};   // only touched on the JavaFX thread
        AtomicReference<Future<?>> task = new AtomicReference<>();   // the large lane's, once moved there
        Future<?> first = lane.submit(guarded(file, cancelled, done,
                () -> decodeSteps(file, extension, minResolution, preload, start, cancelled, cancelJavaFX, task,
                        done)));
        task.compareAndSet(null, first);   // unless the steps already moved to the large lane
        return () -> {
            cancelled.set(true);
            // A load not started yet never runs; a running ImageIO decode is interrupted. A large JavaFX one goes on
            // to the end (it can't be stopped), so only one runs at a time, and its result is dropped.
            task.get().cancel(true);
            cancelJavaFX[0].run();
        };
    }

    /**
     * Never leaves a load unanswered: an unexpected error (a native library failing, say) would otherwise vanish inside
     * the Future and the image would stay "loading" for ever.
     */
    private static Runnable guarded(Path file, AtomicBoolean cancelled, BiConsumer<Loaded<Image>, Object> done,
                                    Runnable steps) {
        return () -> {
            try {
                steps.run();
            } catch (Throwable e) {
                LOG.log(System.Logger.Level.WARNING, "Can't load " + file, e);
                answer(cancelled, done, Loaded.failed(), null);
            }
        };
    }

    private static void answer(AtomicBoolean cancelled, BiConsumer<Loaded<Image>, Object> done, Loaded<Image> result,
                               Object stamp) {
        Platform.runLater(() -> {
            if (!cancelled.get()) {
                done.accept(result, stamp);
            }
        });
    }

    /**
     * The worker-thread part of {@link #decodeOn}: file state, EXIF, size, the decode-memory check (#20), then the
     * decode or JavaFX's load; a large decode moves to its own lane.
     */
    private void decodeSteps(Path file, String extension, double minResolution, boolean preload, long start,
                             AtomicBoolean cancelled, Runnable[] cancelJavaFX, AtomicReference<Future<?>> task,
                             BiConsumer<Loaded<Image>, Object> done) {
        Stamp stamp = Stamp.of(file);
        ImageHeader header = ImageHeader.read(file);
        boolean imageIO = IMAGEIO_EXTENSIONS.contains(extension);
        if (imageIO) {   // metadata-extractor gives no size for these; the reader does, without decoding
            double[] size = imageIOSize(file, extension);
            header = new ImageHeader(header.orientation(), size[0], size[1]);
        }
        ImageHeader known = header;
        Orientation orientation = header.orientation();
        long need = header.hasSize() ? decodeBytes(extension, header.width(), header.height(),
                decodeScale(header.width(), header.height(), orientation, minResolution, preload), preload) : 0;
        if (need > DECODE_LIMIT || preload && need > PRELOAD_DECODE_LIMIT) {
            answer(cancelled, done, Loaded.tooLarge(header.width(), header.height()), stamp);
            return;
        }
        if (need > PRELOAD_DECODE_LIMIT) {
            Runnable decodeLarge = imageIO
                    ? () -> answer(cancelled, done,
                            decode(file, extension, orientation, minResolution, false).timed(millisSince(start)), stamp)
                    : () -> answer(cancelled, done, loadLargeWithJavaFX(file, known, minResolution, start), stamp);
            Future<?> queued = large.submit(guarded(file, cancelled, done, decodeLarge));
            task.set(queued);
            if (cancelled.get()) {
                queued.cancel(true);   // cancelled while moving: it may not have seen this future
            }
        } else if (imageIO) {
            answer(cancelled, done, decode(file, extension, orientation, minResolution, preload)
                    .timed(millisSince(start)), stamp);
        } else {
            double scale = header.hasSize()
                    ? decodeScale(header.width(), header.height(), orientation, minResolution, preload) : 1;
            Platform.runLater(() -> {
                if (!cancelled.get()) {
                    cancelJavaFX[0] = loadWithJavaFX(file, known, scale, preload, start,
                            result -> done.accept(result, stamp));
                }
            });
        }
    }

    /**
     * What decoding a {@code width} × {@code height} image (as stored) at {@code scale} needs (#20). JavaFX decodes
     * a JPEG, PNG, GIF or BMP at full size whatever size is asked for (GC log, 2026-10-24): 3 bytes per pixel for a
     * JPEG, 4 for the others. ImageIO decodes every n-th pixel, then holds three copies.
     */
    static long decodeBytes(String extension, double width, double height, double scale, boolean preload) {
        if (IMAGEIO_EXTENSIONS.contains(extension)) {
            int step = subsampling(scale, width, height, preload);
            return (long) (Math.ceil(width / step) * Math.ceil(height / step) * IMAGEIO_BYTES_PER_PIXEL);
        }
        int bytesPerPixel = extension.equals("jpg") || extension.equals("jpeg") ? 3 : 4;
        return (long) (width * height * bytesPerPixel);
    }

    /** The stored size from the ImageIO reader's header, without decoding; 0 × 0 when it can't be read. */
    private static double[] imageIOSize(Path file, String extension) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersBySuffix(extension);
        if (!readers.hasNext()) {
            return new double[] {0, 0};
        }
        ImageReader reader = readers.next();
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) {
                return new double[] {0, 0};
            }
            reader.setInput(in);
            return new double[] {reader.getWidth(0), reader.getHeight(0)};
        } catch (IOException | RuntimeException e) {
            return new double[] {0, 0};   // the decode will fail and say so
        } finally {
            reader.dispose();
        }
    }

    /** Stops the worker threads: a decode still running is interrupted. Called when the app closes. */
    public void close() {
        worker.shutdownNow();
        preloader.shutdownNow();
        large.shutdownNow();
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

    private double decodeScale(double width, double height, Orientation orientation, double minResolution,
                               boolean preload) {
        return decodeScale(width, height, orientation, minResolution, preload, screenWidth, screenHeight);
    }

    /**
     * 1 up to {@link #SHRINK_ABOVE_PIXELS}; above, the screen's scale raised to {@code minResolution}, at most 1. A
     * {@code preload} is decoded to no more than {@link #SHRINK_ABOVE_PIXELS} pixels (#55, #57): zoom lock at 25 % gets
     * its 25 % ahead of time, but a huge neighbour is never decoded in full for actual size before it is selected.
     */
    static double decodeScale(double width, double height, Orientation orientation, double minResolution,
                              boolean preload, double screenWidth, double screenHeight) {
        if (width * height <= SHRINK_ABOVE_PIXELS) {
            return 1;
        }
        double screen = screenScale(width, height, orientation, screenWidth, screenHeight);
        double scale = Math.min(1, Math.max(screen, minResolution));
        return preload ? Math.min(scale, preloadCap(width, height)) : scale;
    }

    /** The largest scale whose decoded size stays within {@link #SHRINK_ABOVE_PIXELS}, for preloads (#55). */
    static double preloadCap(double width, double height) {
        return Math.min(1, Math.sqrt(SHRINK_ABOVE_PIXELS / (width * height)));
    }

    /** ImageIO keeps every {@code n}-th pixel: the largest {@code n} whose copy is still at least {@code scale}. */
    static int subsampling(double scale) {
        return Math.max(1, (int) Math.floor(1 / scale + 1e-9));
    }

    /**
     * {@link #subsampling}, raised for a preload until the decoded size (every n-th pixel, rounded up) is within
     * {@link #SHRINK_ABOVE_PIXELS} (#57): one step too few gives 50 MP for a 200 MP TIFF.
     */
    static int subsampling(double scale, double width, double height, boolean preload) {
        int step = subsampling(scale);
        while (preload && Math.ceil(width / step) * Math.ceil(height / step) > SHRINK_ABOVE_PIXELS) {
            step++;
        }
        return step;
    }

    /**
     * The size to ask JavaFX for: rounded up, so the copy has at least {@code scale}; for a preload rounded down when
     * rounding up would pass {@link #SHRINK_ABOVE_PIXELS} (#57: 7473 × 5354 = 40.01 MP for a 229 MP image).
     */
    static double[] requestedSize(double width, double height, double scale, boolean preload) {
        double w = Math.ceil(width * scale);
        double h = Math.ceil(height * scale);
        if (preload && w * h > SHRINK_ABOVE_PIXELS) {
            w = Math.floor(width * scale);
            h = Math.floor(height * scale);
        }
        return new double[] {w, h};
    }

    /**
     * One line for the log: the deepest cause, e.g. "No loader for image data" for a damaged file or
     * "OutOfMemoryError: Java heap space"; JavaFX wraps the real error in an ExecutionException.
     */
    static String reason(Throwable error) {
        Throwable cause = error;
        while (cause != null && cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause == null) {
            return "unknown error";
        }
        String message = cause.getMessage();
        String name = cause.getClass().getSimpleName();
        return message == null || message.isBlank() ? name : name + ": " + message;
    }

    private static double millisSince(long start) {
        return (System.nanoTime() - start) / 1e6;
    }

    /**
     * On the JavaFX thread. With {@code scale} below 1 JavaFX decodes straight to the smaller size. Without a known
     * size (damaged header) the image loads whole, as before, and JavaFX shows what it can.
     */
    private static Runnable loadWithJavaFX(Path file, ImageHeader header, double scale, boolean preload, long start,
                                           Consumer<Loaded<Image>> done) {
        String url = file.toUri().toString();
        double[] size = scale < 1 ? requestedSize(header.width(), header.height(), scale, preload) : null;
        Image image = size != null ? new Image(url, size[0], size[1], true, true, true) : new Image(url, true);
        boolean[] reported = {false};
        Runnable check = () -> {
            if (reported[0]) {
                return;
            }
            if (image.isError()) {
                reported[0] = true;
                if (!(image.getException() instanceof CancellationException)) {   // cancelling is reported as an error
                    LOG.log(System.Logger.Level.WARNING, "Can't load " + file + ": " + reason(image.getException()));
                }
                done.accept(failure(image.getException(), header));
            } else if (image.getProgress() >= 1 && image.getWidth() > 0) {
                reported[0] = true;
                double width = header.hasSize() ? header.width() : image.getWidth();
                double height = header.hasSize() ? header.height() : image.getHeight();
                double resolution = Math.min(1, image.getWidth() / width);
                done.accept(new Loaded<>(image, width, height, header.orientation(), resolution,
                        millisSince(start)));
            }
        };
        image.progressProperty().addListener((obs, old, now) -> check.run());
        image.errorProperty().addListener((obs, old, now) -> check.run());
        check.run();
        return image::cancel;
    }

    /** Out of memory is "too large", not "damaged" (#20); the size is the header's. */
    static Loaded<Image> failure(Throwable error, ImageHeader header) {
        Throwable cause = error;
        while (cause != null && !(cause instanceof OutOfMemoryError) && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause instanceof OutOfMemoryError && header.hasSize()
                ? Loaded.tooLarge(header.width(), header.height()) : Loaded.failed();
    }

    /**
     * On the large lane: JavaFX's load without background loading, so it runs on this thread and the next large one
     * starts only after it (#20).
     */
    private Loaded<Image> loadLargeWithJavaFX(Path file, ImageHeader header, double minResolution, long start) {
        double scale = decodeScale(header.width(), header.height(), header.orientation(), minResolution, false);
        String url = file.toUri().toString();
        double[] size = scale < 1 ? requestedSize(header.width(), header.height(), scale, false) : null;
        Image image = size != null ? new Image(url, size[0], size[1], true, true, false) : new Image(url, false);
        if (image.isError() || image.getWidth() <= 0) {
            LOG.log(System.Logger.Level.WARNING, "Can't load " + file + ": " + reason(image.getException()));
            return failure(image.getException(), header);
        }
        double resolution = Math.min(1, image.getWidth() / header.width());
        return new Loaded<>(image, header.width(), header.height(), header.orientation(), resolution,
                millisSince(start));
    }

    /**
     * The first image in the file (page 1 of a TIFF, frame 1 of an animated WebP), every n-th pixel when that still
     * covers the screen and {@code minResolution}. The reader is chosen by extension, since TGA has no signature to
     * recognise it by.
     */
    private Loaded<Image> decode(Path file, String extension, Orientation orientation, double minResolution,
                                 boolean preload) {
        double width = 0;
        double height = 0;
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
                width = reader.getWidth(0);
                height = reader.getHeight(0);
                ImageReadParam param = reader.getDefaultReadParam();
                int step = subsampling(decodeScale(width, height, orientation, minResolution, preload), width, height,
                        preload);
                if (step > 1) {
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                decoded = reader.read(0, param);
            } finally {
                reader.dispose();
            }
            double resolution = Math.min(1, decoded.getWidth() / width);
            return new Loaded<>(toFxImage(decoded), width, height, orientation, resolution, 0);
        } catch (OutOfMemoryError e) {
            // The large arrays are gone by now, so this is recoverable: "too large", not "damaged" (#20).
            return width > 0 && height > 0 ? Loaded.tooLarge(width, height) : Loaded.failed();
        } catch (IOException | RuntimeException e) {
            return Loaded.failed();   // damaged or unsupported: a message, never a hang
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
