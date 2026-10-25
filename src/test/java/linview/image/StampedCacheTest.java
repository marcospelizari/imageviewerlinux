package linview.image;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StampedCacheTest {

    @TempDir
    Path folder;

    /** #66: a thumbnail made before the file was edited is not used again. */
    @Test
    void aChangedFileIsNotUsedAgain() throws IOException {
        Path file = Files.writeString(folder.resolve("a.jpg"), "old");
        StampedCache<String> cache = new StampedCache<>(10);
        cache.put(file, FileStamp.of(file), "old thumbnail");
        assertTrue(cache.get(file).isFor(FileStamp.of(file)));

        Files.writeString(file, "edited");
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 2000));
        assertFalse(cache.get(file).isFor(FileStamp.of(file)));
    }

    /**
     * #66 review: an edit within the same second, same size, is still seen; the freedesktop cache, in whole seconds,
     * would not see it, so a changed entry tells the loader to make the thumbnail again.
     */
    @Test
    void anEditInTheSameSecondIsSeen() throws IOException {
        Path file = Files.writeString(folder.resolve("a.jpg"), "aaaa");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_700_000_000_100L));
        StampedCache<String> cache = new StampedCache<>(10);
        cache.put(file, FileStamp.of(file), "old thumbnail");

        Files.writeString(file, "bbbb");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_700_000_000_900L));
        assertFalse(cache.get(file).isFor(FileStamp.of(file)));
    }

    /** #66: "no thumbnail" is kept too, and a repaired file is tried again. */
    @Test
    void aRepairedFileIsTriedAgain() throws IOException {
        Path file = Files.writeString(folder.resolve("a.jpg"), "damaged");
        StampedCache<String> cache = new StampedCache<>(10);
        cache.put(file, FileStamp.of(file), null);
        assertTrue(cache.get(file).isFor(FileStamp.of(file)));
        assertNull(cache.get(file).value());

        Files.writeString(file, "repaired, longer");
        assertFalse(cache.get(file).isFor(FileStamp.of(file)));
    }

    @Test
    void theLeastRecentlyUsedGoFirst() throws IOException {
        Path a = Files.writeString(folder.resolve("a.jpg"), "a");
        Path b = Files.writeString(folder.resolve("b.jpg"), "b");
        Path c = Files.writeString(folder.resolve("c.jpg"), "c");
        StampedCache<String> cache = new StampedCache<>(2);
        cache.put(a, FileStamp.of(a), "a");
        cache.put(b, FileStamp.of(b), "b");
        cache.get(a);   // a is used: b is now the oldest
        cache.put(c, FileStamp.of(c), "c");
        assertNotNull(cache.get(a));
        assertNull(cache.get(b));
        assertNotNull(cache.get(c));
    }
}
