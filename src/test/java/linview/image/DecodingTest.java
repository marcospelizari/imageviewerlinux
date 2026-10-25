package linview.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import linview.viewer.ImageList;

/** R-FMT-02: which formats go to ImageIO and that its readers are installed; decode memory and its limits (#20). */
class DecodingTest {

    @Test
    void theTwelveMonkeysReadersAreInstalled() {
        for (String extension : Decoding.IMAGEIO_EXTENSIONS) {
            assertTrue(ImageIO.getImageReadersBySuffix(extension).hasNext(), extension);
        }
    }

    @Test
    void everyImageIOFormatIsListed() {
        assertTrue(ImageList.EXTENSIONS.containsAll(Decoding.IMAGEIO_EXTENSIONS));
    }

    /** JavaFX keeps the formats it reads itself: fastest, and animated GIFs play. */
    @Test
    void javaFXFormatsStayWithJavaFX() {
        for (String extension : ImageList.EXTENSIONS) {
            boolean javaFX = Set.of("jpg", "jpeg", "png", "gif", "bmp").contains(extension);
            assertEquals(!javaFX, Decoding.IMAGEIO_EXTENSIONS.contains(extension), extension);
        }
    }

    @Test
    void extensionIgnoresCase() {
        assertEquals("tif", Decoding.extension(Path.of("/pics/Scan.TIF")));
        assertEquals("tga", Decoding.extension(Path.of("earth.tar.tga")));
        assertEquals("", Decoding.extension(Path.of("noextension")));
    }

    /** ImageIO keeps every n-th pixel; the copy is never smaller than the screen needs. */
    @Test
    void subsamplingNeverGoesBelowTheScale() {
        assertEquals(1, Decoding.subsampling(1));
        assertEquals(1, Decoding.subsampling(0.6));
        assertEquals(2, Decoding.subsampling(0.5));
        assertEquals(3, Decoding.subsampling(0.32));
        assertEquals(4, Decoding.subsampling(0.25));
        assertEquals(9, Decoding.subsampling(1080.0 / 10751));
    }

    /**
     * #20: JavaFX decodes a JPEG at full size, 3 bytes per pixel, whatever size is asked for (GC log, 2026-10-24); the
     * other JavaFX formats 4. ImageIO decodes every n-th pixel and holds three copies of it.
     */
    @Test
    void decodeMemoryIsEstimatedFromTheStoredSize() {
        assertEquals(17869L * 12802 * 3, Decoding.decodeBytes("jpg", 17869, 12802, 10));
        assertEquals(4000L * 3000 * 4, Decoding.decodeBytes("png", 4000, 3000, 1));
        assertEquals(1000L * 500 * 12, Decoding.decodeBytes("tif", 2000, 1000, 2));
    }

    /** #20: the limits agreed with Marcos, for the test images from §7. */
    @Test
    void hugeJpegsAreNotPreloadedButFitTheDecodeLimit() {
        long large = Decoding.decodeBytes("jpg", 17869, 12802, 1);    // 25mb.jpg, 229 MP
        long larger = Decoding.decodeBytes("jpg", 29437, 10751, 1);   // 50mb.jpg, 317 MP
        assertTrue(large > Decoding.PRELOAD_DECODE_LIMIT);
        assertTrue(larger <= Decoding.DECODE_LIMIT);
        assertTrue(Decoding.decodeBytes("jpg", 4000, 3000, 1) < Decoding.PRELOAD_DECODE_LIMIT);   // 12 MP
    }
}
