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
}
