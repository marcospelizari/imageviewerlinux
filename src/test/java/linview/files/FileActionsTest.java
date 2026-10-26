package linview.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;

import linview.image.FileStamp;
import linview.image.ImageHeader;
import linview.image.Orientation;

/** R-FILE-01 (#34): moving to the Trash, and that a failure never deletes the file (R5); saving a rotation (#51). */
class FileActionsTest {

    @TempDir
    Path folder;

    /** For the real gio, which refuses files under /tmp ("system internal mounts"): a folder in build/ instead. */
    private Path homeFolder;

    @AfterEach
    void removeHomeFolder() throws IOException {
        if (homeFolder != null) {
            try (Stream<Path> all = Files.walk(homeFolder)) {
                for (Path p : all.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
    }

    @Test
    void trashFailureKeepsTheFileAndSaysWhy() throws IOException {
        Path file = Files.writeString(folder.resolve("a.jpg"), "image");
        // Stands in for gio when the Trash can't take the file; the path arrives as $0.
        FileActions actions = new FileActions(List.of("sh", "-c", "echo \"no trash for $0\" >&2; exit 1"));
        IOException e = assertThrows(IOException.class, () -> actions.trash(file));
        assertEquals("no trash for " + file, e.getMessage());
        assertEquals("image", Files.readString(file));
    }

    @Test
    void missingTrashToolKeepsTheFile() throws IOException {
        Path file = Files.writeString(folder.resolve("a.jpg"), "image");
        FileActions actions = new FileActions(List.of("linview-no-such-tool"));
        IOException e = assertThrows(IOException.class, () -> actions.trash(file));
        assertEquals("The Trash isn't available: linview-no-such-tool was not found", e.getMessage());
        assertTrue(Files.exists(file));
    }

    /** The real gio, with a Trash inside the test folder; skipped where gio isn't installed (CI). */
    @Test
    void gioMovesTheFileToTheTrash() throws IOException {
        assumeTrue(onPath("gio"), "gio not installed");
        homeFolder = Files.createTempDirectory(Files.createDirectories(Path.of("build")), "trash-test");
        Path data = Files.createDirectory(homeFolder.resolve("data"));
        Path file = Files.writeString(homeFolder.resolve("name with spaces, ação.jpg"), "image");
        FileActions actions = new FileActions(List.of("env", "XDG_DATA_HOME=" + data, "gio", "trash", "--"));
        actions.trash(file);
        assertFalse(Files.exists(file));
        Path trashed = data.resolve("Trash/files").resolve(file.getFileName());
        assertEquals("image", Files.readString(trashed));
        assertTrue(Files.exists(data.resolve("Trash/info/" + file.getFileName() + ".trashinfo")));
    }

    @Test
    void gioRefusesAMissingFile() {
        assumeTrue(onPath("gio"), "gio not installed");
        FileActions actions = new FileActions(List.of("env", "XDG_DATA_HOME=" + folder, "gio", "trash", "--"));
        assertThrows(IOException.class, () -> actions.trash(folder.resolve("missing.jpg")));
    }

    private static boolean onPath(String tool) {
        return Stream.of(System.getenv("PATH").split(":")).anyMatch(dir -> Files.isExecutable(Path.of(dir, tool)));
    }

    private static final FileTime LAST_YEAR = FileTime.from(Instant.parse("2025-06-01T10:00:00Z"));

    /** A 30 × 20 image as Java writes it, dated last year, readable by its owner and group only. */
    private Path image(String name, String format) throws IOException {
        Path file = folder.resolve(name);
        ImageIO.write(new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB), format, file.toFile());
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
        Files.setLastModifiedTime(file, LAST_YEAR);
        return file;
    }

    @Test
    void savedRotationKeepsTheDateAndPermissions() throws IOException {
        Path jpeg = image("a.jpg", "jpg");
        new FileActions().saveRotation(jpeg, FileStamp.of(jpeg), Orientation.UPRIGHT, 30, 20, 1);
        assertEquals(6, ImageHeader.read(jpeg).orientation().exifValue());
        assertEquals(LAST_YEAR, Files.getLastModifiedTime(jpeg));
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(jpeg)));

        Path png = image("b.png", "png");
        new FileActions().saveRotation(png, FileStamp.of(png), Orientation.UPRIGHT, 30, 20, 3);
        assertEquals(20, ImageHeader.read(png).width());
        assertEquals(LAST_YEAR, Files.getLastModifiedTime(png));
        try (var left = Files.list(folder)) {
            assertEquals(2, left.count());   // no temporary file left
        }
    }

    /** The file isn't the image that was shown any more (another program changed it): nothing is written. */
    @Test
    void aChangedFileIsNotSaved() throws IOException {
        Path jpeg = image("a.jpg", "jpg");
        byte[] before = Files.readAllBytes(jpeg);
        FileActions actions = new FileActions();
        IOException e = assertThrows(IOException.class,
                () -> actions.saveRotation(jpeg, FileStamp.of(jpeg), Orientation.UPRIGHT.rotated(1), 30, 20, 1));
        assertEquals("the file changed since it was shown", e.getMessage());
        assertThrows(IOException.class,
                () -> actions.saveRotation(jpeg, FileStamp.of(jpeg), Orientation.UPRIGHT, 40, 20, 1));
        assertArrayEquals(before, Files.readAllBytes(jpeg));
    }

    /**
     * #69: another program replaced the file with an image of the same size and orientation after it was loaded: the
     * stamp from loading no longer matches, so nothing is written.
     */
    @Test
    void aSameSizeReplacementIsNotSaved() throws IOException {
        Path png = image("a.png", "png");
        FileStamp loaded = FileStamp.of(png);
        BufferedImage other = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);
        other.setRGB(0, 0, 0xffffff);
        ImageIO.write(other, "png", png.toFile());
        Files.setLastModifiedTime(png, LAST_YEAR);   // as a date-keeping editor would
        byte[] replaced = Files.readAllBytes(png);
        IOException e = assertThrows(IOException.class,
                () -> new FileActions().saveRotation(png, loaded, Orientation.UPRIGHT, 30, 20, 1));
        assertEquals("the file changed since it was shown", e.getMessage());
        assertArrayEquals(replaced, Files.readAllBytes(png));
    }

    /** Skipped as root (the CI container), who may write a read-only file. */
    @Test
    void aReadOnlyFileIsRefused() throws IOException {
        Path locked = image("a.jpg", "jpg");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r--r-----"));
        assumeFalse(Files.isWritable(locked), "running as root");
        byte[] before = Files.readAllBytes(locked);
        IOException e = assertThrows(IOException.class,
                () -> new FileActions().saveRotation(locked, FileStamp.of(locked), Orientation.UPRIGHT, 30, 20, 1));
        assertEquals("no permission to change the file", e.getMessage());
        assertArrayEquals(before, Files.readAllBytes(locked));
    }

    @Test
    void otherFormatsAndMissingFilesAreRefused() throws IOException {
        FileActions actions = new FileActions();
        image("a.jpg", "jpg");
        Path gif = image("b.gif", "gif");
        assertFalse(FileActions.canSaveRotation(gif));
        assertThrows(IOException.class,
                () -> actions.saveRotation(gif, FileStamp.of(gif), Orientation.UPRIGHT, 30, 20, 1));
        assertThrows(IOException.class,
                () -> actions.saveRotation(folder.resolve("missing.jpg"), null, Orientation.UPRIGHT, 30, 20, 1));
        try (var left = Files.list(folder)) {
            assertEquals(2, left.count());
        }
    }
}
