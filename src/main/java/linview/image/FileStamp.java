package linview.image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

/**
 * A file's modified time, size and change time: what is kept from it is used only while all three are the same (R4).
 * The change time catches writes that keep the modified time, such as saving a rotation (#51) or {@code exiftool -P}.
 * Read it off the JavaFX thread, a USB disk or network mount can be slow.
 */
public record FileStamp(FileTime modified, long size, FileTime changed) {

    /** {@code null} when the file can't be read, e.g. deleted. */
    public static FileStamp of(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return new FileStamp(attributes.lastModifiedTime(), attributes.size(), changeTime(file));
        } catch (IOException e) {
            return null;
        }
    }

    /** {@code null} on a system without Unix attributes (not Linux). */
    private static FileTime changeTime(Path file) throws IOException {
        try {
            return (FileTime) Files.getAttribute(file, "unix:ctime");
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }
}
