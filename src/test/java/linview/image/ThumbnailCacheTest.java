package linview.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #28: the freedesktop thumbnail cache, shared with file managers. */
class ThumbnailCacheTest {

    @TempDir
    Path home;

    /** The example in the Thumbnail Managing Standard. */
    @Test
    void theFileNameIsTheMd5OfTheUri() {
        assertEquals("c6ee772d9e49320e97ec29a7eb5b1697", ThumbnailCache.md5("file:///home/jens/photos/me.png"));
        assertEquals("file:///home/jens/photos/me.png", ThumbnailCache.uri(Path.of("/home/jens/photos/me.png")));
    }

    @Test
    void spacesAndAccentsAreEscapedInTheUri() {
        assertEquals("file:///pics/F%C3%A9rias%202026/a.jpg", ThumbnailCache.uri(Path.of("/pics/Férias 2026/a.jpg")));
    }

    @Test
    void textChunksAreAddedAfterTheHeaderAndReadBack() throws IOException {
        byte[] png = png(4, 3);
        byte[] tagged = ThumbnailCache.withText(png, Map.of("Thumb::URI", "file:///a.jpg", "Thumb::MTime", "123"));

        assertEquals(Map.of("Thumb::URI", "file:///a.jpg", "Thumb::MTime", "123"), ThumbnailCache.textChunks(tagged));
        BufferedImage back = ImageIO.read(new java.io.ByteArrayInputStream(tagged));   // still a valid PNG
        assertEquals(4, back.getWidth());
        assertTrue(ThumbnailCache.textChunks(png).isEmpty());
        assertTrue(ThumbnailCache.textChunks(new byte[] {1, 2, 3}).isEmpty());
    }

    @Test
    void aStoredThumbnailIsFoundWhileTheFileIsUnchanged() throws IOException {
        ThumbnailCache cache = new ThumbnailCache(home.resolve("thumbnails"));
        Path photo = home.resolve("photo.jpg");

        cache.store(photo, 1_700_000_000L, new BufferedImage(256, 192, BufferedImage.TYPE_INT_ARGB));

        Path found = cache.find(photo, 1_700_000_000L);
        assertNotNull(found);
        assertEquals(home.resolve("thumbnails/large/" + ThumbnailCache.md5(ThumbnailCache.uri(photo)) + ".png"), found);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(found)));
        assertNull(cache.find(photo, 1_700_000_001L));   // the file changed since
        assertNull(cache.find(home.resolve("other.jpg"), 1_700_000_000L));
        try (var left = Files.list(found.getParent())) {
            assertEquals(1, left.count());   // no temporary file left behind
        }
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out);
        return out.toByteArray();
    }
}
