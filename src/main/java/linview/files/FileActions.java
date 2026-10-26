package linview.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import linview.image.FileStamp;
import linview.image.ImageHeader;
import linview.image.Orientation;

/**
 * Changes to the user's files (REPORT.md §6.4): moving one to the Trash (R-FILE-01, #34) and saving a rotation
 * (R-FILE-03, #51). Each action either
 * succeeds or throws with a message for the user and leaves the file as it was. No UI, so it can be tested without a
 * screen. Blocks while it works: call it off the JavaFX thread.
 */
public final class FileActions {

    /**
     * The desktop's own tool (Marcos, 2026-10-26): it follows the freedesktop Trash rules, including a drive's own
     * {@code .Trash-$UID} for files on a USB drive, so Dolphin shows them. Linux only; Windows / macOS would use
     * {@code Desktop.moveToTrash} when such a build is planned.
     */
    /** Formats a rotation can be saved to (#51): JPEG through its EXIF tag, PNG and BMP written again. */
    private static final List<String> ROTATION_FORMATS = List.of("jpg", "jpeg", "png", "bmp");

    private static final List<String> GIO_TRASH = List.of("gio", "trash", "--");

    private final List<String> trashCommand;

    public FileActions() {
        this(GIO_TRASH);
    }

    /** {@code trashCommand} gets the file's absolute path appended; tests use a stand-in. */
    FileActions(List<String> trashCommand) {
        this.trashCommand = List.copyOf(trashCommand);
    }

    /**
     * Moves {@code file} to the Trash. Never deletes it instead: if the Trash can't take it, the file stays (R5).
     *
     * @throws IOException with a message for the user; the file is then where it was
     */
    public void trash(Path file) throws IOException {
        List<String> command = new ArrayList<>(trashCommand);
        command.add(file.toAbsolutePath().toString());   // absolute, so gio never reads the name as a URI
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException("The Trash isn't available: " + trashCommand.get(0) + " was not found", e);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while moving to the Trash", e);
        }
        if (exit != 0) {
            throw new IOException(output.isEmpty() ? trashCommand.get(0) + " failed (exit " + exit + ")" : output);
        }
    }

    /** Whether {@link #saveRotation} can write {@code file}'s format; other formats keep a rotation in the view only. */
    public static boolean canSaveRotation(Path file) {
        return ROTATION_FORMATS.contains(extension(file));
    }

    /**
     * Saves a view rotation to {@code file} (R-FILE-03, #51): {@code quarterTurns} × 90° clockwise on top of
     * {@code stored}, the orientation it was shown with, its stored size {@code width} × {@code height}, its
     * {@link FileStamp} {@code shown} when it was loaded (#69): a file changed since is refused. A JPEG gets
     * only a new EXIF orientation tag, its image data untouched; a PNG or BMP is written again, lossless. The modified
     * date stays. Written to a temporary file next to it, then moved over it in one step.
     *
     * @throws IOException with a message for the user, e.g. the file changed since it was shown, no permission, or the
     *                     disk is full; the file is then as it was
     */
    public void saveRotation(Path file, Object shown, Orientation stored, double width, double height,
                             int quarterTurns) throws IOException {
        Path target = file.toRealPath();   // a link keeps pointing at the saved file
        String format = extension(target);
        if (!canSaveRotation(target)) {
            throw new IOException("a rotation can't be saved to " + format.toUpperCase(Locale.ROOT) + " files");
        }
        if (!Files.isWritable(target)) {
            throw new IOException("no permission to change the file");
        }
        FileStamp before = FileStamp.of(target);
        ImageHeader header = ImageHeader.read(target);
        if (before == null || !before.equals(shown) || !header.orientation().equals(stored) || header.width() != width
                || header.height() != height) {
            throw new IOException("the file changed since it was shown");
        }
        byte[] original = Files.readAllBytes(target);
        Orientation turned = stored.rotated(quarterTurns);
        byte[] saved;
        try {
            saved = format.equals("png") || format.equals("bmp")
                    ? TurnedImage.turned(original, format, turned)
                    : JpegOrientation.withOrientation(original, turned.exifValue());
        } catch (OutOfMemoryError e) {
            throw new IOException("the image is too large to save", e);
        }
        replace(target, saved, before);
    }

    /** {@code target}'s content replaced by {@code content} in one step, with its permissions and modified date. */
    private static void replace(Path target, byte[] content, FileStamp before) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".linview");
        try {
            Files.write(temporary, content);
            Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(target));
            Files.setLastModifiedTime(temporary, before.modified());
            if (!before.equals(FileStamp.of(target))) {
                throw new IOException("the file changed while saving");
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
