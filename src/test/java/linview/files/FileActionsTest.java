package linview.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R-FILE-01 (#34): moving to the Trash, and that a failure never deletes the file (R5). */
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
}
