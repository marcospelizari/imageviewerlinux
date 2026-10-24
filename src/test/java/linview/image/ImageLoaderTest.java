package linview.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import linview.viewer.ImageList;

/**
 * R-FMT-02: which formats go to ImageIO and that its readers are installed. Decoding itself needs JavaFX and
 * real images, so it is checked by hand with the test folder (REPORT.md §7).
 */
class ImageLoaderTest {

    @Test
    void theTwelveMonkeysReadersAreInstalled() {
        for (String extension : ImageLoader.IMAGEIO_EXTENSIONS) {
            assertTrue(ImageIO.getImageReadersBySuffix(extension).hasNext(), extension);
        }
    }

    @Test
    void everyImageIOFormatIsListed() {
        assertTrue(ImageList.EXTENSIONS.containsAll(ImageLoader.IMAGEIO_EXTENSIONS));
    }

    /** JavaFX keeps the formats it reads itself: fastest, and animated GIFs play. */
    @Test
    void javaFXFormatsStayWithJavaFX() {
        for (String extension : ImageList.EXTENSIONS) {
            boolean javaFX = Set.of("jpg", "jpeg", "png", "gif", "bmp").contains(extension);
            assertEquals(!javaFX, ImageLoader.IMAGEIO_EXTENSIONS.contains(extension), extension);
        }
    }

    @Test
    void extensionIgnoresCase() {
        assertEquals("tif", ImageLoader.extension(Path.of("/pics/Scan.TIF")));
        assertEquals("tga", ImageLoader.extension(Path.of("earth.tar.tga")));
        assertEquals("", ImageLoader.extension(Path.of("noextension")));
    }

    /** #19: just enough to cover a 1920×1080 screen upright, in both directions; never enlarged. */
    @Test
    void screenScaleCoversTheScreenUpright() {
        assertEquals(0.32, ImageLoader.screenScale(6000, 4000, Orientation.UPRIGHT, 1920, 1080), 1e-9);
        // Stored landscape, shown upright as portrait (EXIF 6): 4000 wide must still cover 1920.
        assertEquals(0.48, ImageLoader.screenScale(6000, 4000, Orientation.of(6), 1920, 1080), 1e-9);
        // A panorama covers the screen height: 10751 → 1080.
        assertEquals(1080.0 / 10751, ImageLoader.screenScale(29437, 10751, Orientation.UPRIGHT, 1920, 1080), 1e-9);
        assertEquals(1, ImageLoader.screenScale(1200, 1800, Orientation.UPRIGHT, 1920, 1080));
        assertEquals(1, ImageLoader.screenScale(400, 300, Orientation.UPRIGHT, 1920, 1080));
    }

    /** ImageIO keeps every n-th pixel; the copy is never smaller than the screen needs. */
    @Test
    void subsamplingNeverGoesBelowTheScale() {
        assertEquals(1, ImageLoader.subsampling(1));
        assertEquals(1, ImageLoader.subsampling(0.6));
        assertEquals(2, ImageLoader.subsampling(0.5));
        assertEquals(3, ImageLoader.subsampling(0.32));
        assertEquals(4, ImageLoader.subsampling(0.25));
        assertEquals(9, ImageLoader.subsampling(1080.0 / 10751));
    }

    /** Normal photos load whole, as before; only very large images get a screen-size copy. */
    @Test
    void onlyVeryLargeImagesAreShrunk() {
        assertEquals(1, ImageLoader.decodeScale(6000, 4000, Orientation.UPRIGHT, 0, false, 1920, 1080));   // 24 MP
        assertEquals(1, ImageLoader.decodeScale(7745, 5164, Orientation.UPRIGHT, 0, false, 1920, 1080));   // ≈ 40 MP
        assertEquals(1920.0 / 17869, ImageLoader.decodeScale(17869, 12802, Orientation.UPRIGHT, 0, false, 1920, 1080), 1e-9);
        // The zoom mode can ask for more: zoom lock at 50 %, actual size.
        assertEquals(0.5, ImageLoader.decodeScale(17869, 12802, Orientation.UPRIGHT, 0.5, false, 1920, 1080));
        assertEquals(1, ImageLoader.decodeScale(17869, 12802, Orientation.UPRIGHT, 1, false, 1920, 1080));
    }

    /** #55: a preload asks for what the zoom needs, but never more than 40 MP decoded. */
    @Test
    void preloadsOfHugeImagesStayWithin40Megapixels() {
        double cap317 = Math.sqrt(40e6 / (29437.0 * 10751));   // ≈ 0.355
        // Zoom lock at 25 %: within the cap, so the preload is as sharp as the zoom.
        assertEquals(0.25, ImageLoader.decodeScale(29437, 10751, Orientation.UPRIGHT, 0.25, true, 1920, 1080));
        // Actual size: capped; the full image waits until it is selected.
        assertEquals(cap317, ImageLoader.decodeScale(29437, 10751, Orientation.UPRIGHT, 1, true, 1920, 1080), 1e-9);
        assertEquals(1, ImageLoader.decodeScale(29437, 10751, Orientation.UPRIGHT, 1, false, 1920, 1080));
        // Fit: the screen copy, as for the selected image.
        assertEquals(1080.0 / 10751, ImageLoader.decodeScale(29437, 10751, Orientation.UPRIGHT, 0, true, 1920, 1080),
                1e-9);
        // Normal photos load whole either way.
        assertEquals(1, ImageLoader.decodeScale(6000, 4000, Orientation.UPRIGHT, 1, true, 1920, 1080));
    }

    /** #57: a capped preload really stays within 40 MP, after JavaFX's rounding. */
    @Test
    void aCappedJavaFXPreloadIsRoundedDownToStayWithinTheCap() {
        double cap = ImageLoader.preloadCap(17869, 12802);
        double[] rounded = ImageLoader.requestedSize(17869, 12802, cap, true);
        assertTrue(rounded[0] * rounded[1] <= 40e6, rounded[0] + "×" + rounded[1]);
        // Rounding up would pass the cap (7473 × 5354 = 40.01 MP); the selected image keeps rounding up.
        assertTrue(Math.ceil(17869 * cap) * Math.ceil(12802 * cap) > 40e6);
        assertEquals(Math.ceil(17869 * cap), ImageLoader.requestedSize(17869, 12802, cap, false)[0]);
        // Zoom lock at 25 % is well inside the cap: rounded up, so the copy counts as 25 % when selected.
        assertEquals(4468, ImageLoader.requestedSize(17869, 12802, 0.25, true)[0]);
    }

    /** #57: ImageIO's whole-number subsampling is raised for a preload until it fits 40 MP. */
    @Test
    void aCappedImageIOPreloadUsesEnoughSubsampling() {
        double cap = ImageLoader.preloadCap(20000, 10000);   // 200 MP TIFF: ≈ 0.447
        assertEquals(2, ImageLoader.subsampling(cap, 20000, 10000, false));   // 10000 × 5000 = 50 MP
        int step = ImageLoader.subsampling(cap, 20000, 10000, true);
        assertEquals(3, step);
        assertTrue(Math.ceil(20000.0 / step) * Math.ceil(10000.0 / step) <= 40e6);
    }

    /** A panorama whose screen copy alone would pass 40 MP is capped for a preload too. */
    @Test
    void aPanoramaPreloadIsCappedBelowItsScreenCopy() {
        double cap = ImageLoader.preloadCap(100000, 1000);
        assertEquals(cap, ImageLoader.decodeScale(100000, 1000, Orientation.UPRIGHT, 0, true, 1920, 1080), 1e-9);
        assertEquals(1, ImageLoader.decodeScale(100000, 1000, Orientation.UPRIGHT, 0, false, 1920, 1080));
    }

    /** #57: a load error is one line in the log, with the real cause JavaFX wraps. */
    @Test
    void reasonIsTheDeepestCauseOnOneLine() {
        assertEquals("OutOfMemoryError: Java heap space", ImageLoader.reason(
                new java.util.concurrent.ExecutionException(new OutOfMemoryError("Java heap space"))));
        assertEquals("IOException: No loader for image data",
                ImageLoader.reason(new java.io.IOException("No loader for image data")));
        assertEquals("IllegalStateException", ImageLoader.reason(new IllegalStateException()));
        assertEquals("unknown error", ImageLoader.reason(null));
    }

    /**
     * #20: JavaFX decodes a JPEG at full size, 3 bytes per pixel, whatever size is asked for (GC log, 2026-10-24); the
     * other JavaFX formats 4. ImageIO decodes every n-th pixel and holds three copies of it.
     */
    @Test
    void decodeMemoryIsEstimatedFromTheStoredSize() {
        assertEquals(17869L * 12802 * 3, ImageLoader.decodeBytes("jpg", 17869, 12802, 0.1, false));
        assertEquals(4000L * 3000 * 4, ImageLoader.decodeBytes("png", 4000, 3000, 1, false));
        assertEquals(1000L * 500 * 12, ImageLoader.decodeBytes("tif", 2000, 1000, 0.5, false));
    }

    /** #20: the limits agreed with Marcos, for the test images from §7. */
    @Test
    void hugeJpegsAreNotPreloadedButFitTheDecodeLimit() {
        long large = ImageLoader.decodeBytes("jpg", 17869, 12802, 1, false);    // 25mb.jpg, 229 MP
        long larger = ImageLoader.decodeBytes("jpg", 29437, 10751, 1, false);   // 50mb.jpg, 317 MP
        assertTrue(large > ImageLoader.PRELOAD_DECODE_LIMIT);
        assertTrue(larger <= ImageLoader.DECODE_LIMIT);
        assertTrue(ImageLoader.decodeBytes("jpg", 4000, 3000, 1, true) < ImageLoader.PRELOAD_DECODE_LIMIT);   // 12 MP
    }

    /** #20: running out of memory is "too large", whatever wraps it; any other error stays "damaged". */
    @Test
    void outOfMemoryIsTooLargeNotDamaged() {
        ImageHeader header = new ImageHeader(Orientation.UPRIGHT, 29437, 10751);
        assertTrue(ImageLoader.failure(new java.util.concurrent.ExecutionException(
                new OutOfMemoryError("Java heap space")), header).isTooLarge());
        assertFalse(ImageLoader.failure(new java.io.IOException("No loader"), header).isTooLarge());
        assertTrue(ImageLoader.failure(new java.io.IOException("No loader"), header).isFailed());
        assertFalse(ImageLoader.failure(new OutOfMemoryError(), ImageHeader.UNKNOWN).isTooLarge());
        assertTrue(ImageLoader.failure(null, header).isFailed());
    }
}
