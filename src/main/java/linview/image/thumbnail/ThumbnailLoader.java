package linview.image.thumbnail;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import linview.image.Decoding;
import linview.image.FileStamp;
import linview.image.ImageHeader;
import linview.image.ImageResampler;
import linview.image.Orientation;

/**
 * Thumbnails for the strip (#28), on one background thread of its own, so the selected image and preloads never wait
 * for them. A valid thumbnail in the freedesktop cache ({@link ThumbnailCache}) is used as it is, else one is made,
 * turned upright and stored there for file managers too. The newest request goes first: the cells just scrolled into
 * view. An image whose decode would need more than {@link Decoding#PRELOAD_DECODE_LIMIT} gets none (#20). The
 * last {@link #KEPT} thumbnails (and "none" answers) stay in memory, used only while the file's modified time and size
 * are the same, checked on the thread (#66). Public methods are called on the JavaFX thread, and answer on it.
 */
public final class ThumbnailLoader {

    /** ≈ 200 KB each at 256 × 192: ≈ 60 MB. */
    static final int KEPT = 300;

    private static final System.Logger LOG = System.getLogger("linview");

    private final ThumbnailCache cache;
    private final LinkedBlockingDeque<Path> queue = new LinkedBlockingDeque<>();
    /** On the thread only; {@code null} for an image with no thumbnail (damaged, or too large to decode for one). */
    private final StampedCache<Image> kept = new StampedCache<>(KEPT);
    private final Map<Path, List<Consumer<Image>>> waiting = new HashMap<>();
    private final Thread worker;

    public ThumbnailLoader() {
        this(ThumbnailCache.defaultFolder());
    }

    ThumbnailLoader(Path thumbnails) {
        cache = new ThumbnailCache(thumbnails);
        worker = new Thread(this::work, "linview-thumbnails");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    /** Calls {@code done} with the upright thumbnail of {@code file}, or {@code null} if it has none. */
    public void request(Path file, Consumer<Image> done) {
        List<Consumer<Image>> callers = waiting.get(file);
        if (callers != null) {
            callers.add(done);
            if (queue.remove(file)) {   // asked again before it started: to the front
                queue.addLast(file);
            }
            return;
        }
        waiting.put(file, new ArrayList<>(List.of(done)));
        queue.addLast(file);
    }

    /** Drops requests not started yet, e.g. for a folder no longer open; their callers are never answered. */
    public void forgetWaiting() {
        queue.clear();
        waiting.clear();
    }

    public void close() {
        worker.interrupt();
    }

    private void work() {
        while (!Thread.currentThread().isInterrupted()) {
            Path file;
            try {
                file = queue.takeLast();
            } catch (InterruptedException e) {
                return;
            }
            Image result = thumbnailFor(file);
            Platform.runLater(() -> answer(file, result));
        }
    }

    /** On the thread: the one kept while the file is unchanged, else a new one; {@code null} if it has none. */
    private Image thumbnailFor(Path file) {
        FileStamp stamp = FileStamp.of(file);
        if (stamp == null) {
            return null;   // deleted or unreadable: nothing to keep
        }
        StampedCache.Entry<Image> entry = kept.get(file);
        if (entry != null && entry.isFor(stamp)) {
            return entry.value();
        }
        Image thumbnail = null;
        try {
            // A file changed since its thumbnail was made: the cache's MTime is in whole seconds, so its thumbnail
            // may be the old one from the same second; make it again (and store it for file managers too).
            thumbnail = thumbnailOf(file, stamp, entry == null);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            LOG.log(System.Logger.Level.DEBUG, "No thumbnail for " + file + ": " + e);
        }
        kept.put(file, stamp, thumbnail);
        return thumbnail;
    }

    private void answer(Path file, Image thumbnail) {
        List<Consumer<Image>> callers = waiting.remove(file);
        if (callers != null) {
            callers.forEach(done -> done.accept(thumbnail));
        }
    }

    /** On the worker thread: from the cache if {@code fromCache}, else made and stored. */
    private Image thumbnailOf(Path file, FileStamp stamp, boolean fromCache) throws IOException {
        long modified = stamp.modified().toMillis() / 1000;
        Path cached = fromCache ? cache.find(file, modified) : null;
        if (cached != null) {
            Image image = new Image(cached.toUri().toString(), false);
            if (!image.isError()) {
                return image;
            }
        }
        Pixels made = make(file);
        if (made == null) {
            return null;
        }
        BufferedImage stored = new BufferedImage(made.width, made.height, BufferedImage.TYPE_INT_ARGB_PRE);
        System.arraycopy(made.argb, 0, ((DataBufferInt) stored.getRaster().getDataBuffer()).getData(), 0,
                made.argb.length);
        try {
            cache.store(file, modified, stored);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "Can't store the thumbnail of " + file + ": " + e);   // still shown
        }
        WritableImage image = new WritableImage(made.width, made.height);
        image.getPixelWriter().setPixels(0, 0, made.width, made.height, PixelFormat.getIntArgbPreInstance(),
                made.argb, 0, made.width);
        return image;
    }

    /** Premultiplied ARGB, row by row. */
    private record Pixels(int[] argb, int width, int height) {
    }

    /** Decodes {@code file} small and turns it upright; {@code null} if it can't be, or would need too much memory. */
    private static Pixels make(Path file) throws IOException {
        String extension = Decoding.extension(file);
        ImageHeader header = ImageHeader.read(file);
        // JavaFX gives no pixels of an animated GIF; Java's own GIF reader gives its first frame.
        Pixels stored = Decoding.IMAGEIO_EXTENSIONS.contains(extension) || extension.equals("gif")
                ? decodeWithImageIO(file, extension) : decodeWithJavaFX(file, extension, header);
        if (stored == null) {
            return null;
        }
        Orientation orientation = header.orientation();
        int[] upright = orientation.turn(stored.argb, stored.width, stored.height);
        return orientation.swapsSides()
                ? new Pixels(upright, stored.height, stored.width) : new Pixels(upright, stored.width, stored.height);
    }

    /** JavaFX decodes at full size before shrinking, so a huge image is skipped (#20). */
    private static Pixels decodeWithJavaFX(Path file, String extension, ImageHeader header) {
        if (header.hasSize() && Decoding.decodeBytes(extension, header.width(), header.height(), 1)
                > Decoding.PRELOAD_DECODE_LIMIT) {
            return null;
        }
        int box = ThumbnailCache.LARGE;
        Image image = new Image(file.toUri().toString(), box, box, true, true, false);
        if (image.isError() || image.getWidth() <= 0) {
            return null;
        }
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        int[] argb = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbPreInstance(), argb, 0, width);
        return new Pixels(argb, width, height);
    }

    /** Every n-th pixel, still at least the thumbnail's size, then Lanczos down to it (#24). */
    private static Pixels decodeWithImageIO(Path file, String extension) throws IOException {
        Iterator<ImageReader> readers = ImageIO.getImageReadersBySuffix(extension);
        if (!readers.hasNext()) {
            return null;
        }
        ImageReader reader = readers.next();
        BufferedImage decoded;
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) {
                return null;
            }
            reader.setInput(in);
            double width = reader.getWidth(0);
            double height = reader.getHeight(0);
            double scale = Math.min(1, ThumbnailCache.LARGE / Math.max(width, height));
            int step = Decoding.subsampling(scale);
            if (Decoding.decodeBytes(extension, width, height, step) > Decoding.PRELOAD_DECODE_LIMIT) {
                return null;
            }
            ImageReadParam param = reader.getDefaultReadParam();
            if (step > 1) {
                param.setSourceSubsampling(step, step, 0, 0);
            }
            decoded = reader.read(0, param);
        } finally {
            reader.dispose();
        }
        BufferedImage pre = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_ARGB_PRE);
        pre.createGraphics().drawImage(decoded, 0, 0, null);
        int[] argb = ((DataBufferInt) pre.getRaster().getDataBuffer()).getData();
        double fit = Math.min(1, (double) ThumbnailCache.LARGE / Math.max(pre.getWidth(), pre.getHeight()));
        int toWidth = Math.max(1, (int) Math.round(pre.getWidth() * fit));
        int toHeight = Math.max(1, (int) Math.round(pre.getHeight() * fit));
        if (fit >= 1) {
            return new Pixels(argb.clone(), pre.getWidth(), pre.getHeight());
        }
        return new Pixels(ImageResampler.lanczos(argb, pre.getWidth(), pre.getHeight(), toWidth, toHeight,
                () -> false), toWidth, toHeight);
    }
}
