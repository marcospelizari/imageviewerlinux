package linview.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
