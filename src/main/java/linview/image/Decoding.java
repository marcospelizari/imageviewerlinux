package linview.image;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * What decoding an image file takes, the same for the viewer's images ({@link ImageLoader}) and the thumbnails
 * ({@link linview.image.thumbnail.ThumbnailLoader}): which decoder reads it (R-FMT-02), how much memory a decode
 * needs and the limits on that (#20), and ImageIO's subsampling. No JavaFX, so it can be tested without a screen.
 */
public final class Decoding {

    /** Extensions decoded with ImageIO; everything else goes to JavaFX. */
    public static final Set<String> IMAGEIO_EXTENSIONS = Set.of("webp", "tif", "tiff", "psd", "tga");

    /**
     * A decode needing more than this (≈ 100 MP JPEG) is not preloaded and runs one at a time (Marcos, #20); an image
     * needing more gets no thumbnail.
     */
    public static final long PRELOAD_DECODE_LIMIT = 300L << 20;

    /**
     * A decode needing more than this is refused: the 2 GB heap ({@code -Xmx2g}) less the cache and a preload
     * (Marcos, #20).
     */
    public static final long DECODE_LIMIT = 1200L << 20;

    /** The ImageIO path briefly holds the decoded image, its ARGB array and the JavaFX image (REPORT.md §6.8). */
    private static final int IMAGEIO_BYTES_PER_PIXEL = 12;

    private Decoding() {
    }

    public static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * What decoding a {@code width} × {@code height} image (as stored) needs (#20), ImageIO keeping every
     * {@code step}-th pixel. JavaFX decodes a JPEG, PNG, GIF or BMP at full size whatever size is asked for (GC log,
     * 2026-10-24): 3 bytes per pixel for a JPEG, 4 for the others. ImageIO decodes every n-th pixel, then holds
     * three copies.
     */
    public static long decodeBytes(String extension, double width, double height, int step) {
        if (IMAGEIO_EXTENSIONS.contains(extension)) {
            return (long) (Math.ceil(width / step) * Math.ceil(height / step) * IMAGEIO_BYTES_PER_PIXEL);
        }
        int bytesPerPixel = extension.equals("jpg") || extension.equals("jpeg") ? 3 : 4;
        return (long) (width * height * bytesPerPixel);
    }

    /** ImageIO keeps every {@code n}-th pixel: the largest {@code n} whose copy is still at least {@code scale}. */
    public static int subsampling(double scale) {
        return Math.max(1, (int) Math.floor(1 / scale + 1e-9));
    }
}
