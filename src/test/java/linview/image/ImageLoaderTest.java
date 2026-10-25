package linview.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * How large a copy to decode and how preloads stay bounded (#19, #55, #57). Decoding itself needs JavaFX and real
 * images, so it is checked by hand with the test folder (REPORT.md §7).
 */
class ImageLoaderTest {

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
