package linview.image.thumbnail;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import linview.image.FileStamp;

/**
 * What was made from each file, with the {@link FileStamp} it was made at: used only while the file still has it
 * (#66); the least recently used go once there are more than {@code limit}. A value may be {@code null}, e.g. "this
 * file has no thumbnail". For one thread only. {@code V} is the value type (JavaFX {@code Image} in the app), so it
 * can be tested without starting JavaFX.
 */
final class StampedCache<V> {

    record Entry<V>(V value, FileStamp stamp) {

        /** Made from the file as it is now. */
        boolean isFor(FileStamp now) {
            return stamp.equals(now);
        }
    }

    private final int limit;
    /** Least recently used first. */
    private final Map<Path, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);

    StampedCache(int limit) {
        this.limit = limit;
    }

    /** What was kept for {@code file}, maybe from an older version ({@link Entry#isFor}); {@code null} if nothing. */
    Entry<V> get(Path file) {
        return entries.get(file);
    }

    void put(Path file, FileStamp stamp, V value) {
        entries.put(file, new Entry<>(value, stamp));
        var oldest = entries.keySet().iterator();
        while (entries.size() > limit) {
            oldest.next();
            oldest.remove();
        }
    }
}
