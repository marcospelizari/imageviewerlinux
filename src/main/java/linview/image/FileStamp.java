package linview.image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

/**
 * A file's modified time and size: what is kept from it is used only while both are the same (R4). Read it off the
 * JavaFX thread, a USB disk or network mount can be slow.
 */
record FileStamp(FileTime modified, long size) {

    /** {@code null} when the file can't be read, e.g. deleted. */
    static FileStamp of(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return new FileStamp(attributes.lastModifiedTime(), attributes.size());
        } catch (IOException e) {
            return null;
        }
    }
}
