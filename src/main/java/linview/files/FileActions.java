package linview.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Changes to the user's files (REPORT.md §6.4): for now moving one to the Trash (R-FILE-01, #34). Each action either
 * succeeds or throws with a message for the user and leaves the file as it was. No UI, so it can be tested without a
 * screen. Blocks while it works: call it off the JavaFX thread.
 */
public final class FileActions {

    /**
     * The desktop's own tool (Marcos, 2026-10-26): it follows the freedesktop Trash rules, including a drive's own
     * {@code .Trash-$UID} for files on a USB drive, so Dolphin shows them. Linux only; Windows / macOS would use
     * {@code Desktop.moveToTrash} when such a build is planned.
     */
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
}
