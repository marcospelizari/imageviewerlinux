package linview.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #19/#21: the stored size from the header, without Java's native JPEG reader; EXIF orientation (R-FMT-03). */
class ImageHeaderTest {

    @Test
    void readsTheSizeOfPngGifAndBmp(@TempDir Path folder) throws IOException {
        for (String format : new String[] {"png", "gif", "bmp"}) {
            Path file = folder.resolve("small." + format);
            ImageIO.write(new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB), format, file.toFile());

            ImageHeader header = ImageHeader.read(file);

            assertEquals(30, header.width(), format);
            assertEquals(20, header.height(), format);
            assertEquals(Orientation.UPRIGHT, header.orientation(), format);
        }
    }

    @Test
    void unreadableFilesAreShownAsStoredAndLoadWhole(@TempDir Path folder) throws IOException {
        Path junk = Files.write(folder.resolve("junk.jpg"), new byte[] {1, 2, 3, 4, 5});

        assertEquals(ImageHeader.UNKNOWN, ImageHeader.read(junk));
        assertEquals(ImageHeader.UNKNOWN, ImageHeader.read(folder.resolve("missing.jpg")));
        assertFalse(ImageHeader.UNKNOWN.hasSize());
    }
}
