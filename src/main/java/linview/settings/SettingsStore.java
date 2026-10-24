package linview.settings;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import linview.viewer.Zoom;

/**
 * The settings file {@code ~/.config/linview/settings.properties} (R-SET-01). A missing file gives the defaults;
 * an unreadable file or an invalid value gives the default plus a warning, never a crash. The rules for a valid
 * value are the {@link Settings} setters; this class only turns text into values and back. No UI, so it can be
 * tested without a screen. Keys are documented in README.md.
 *
 * <p>It is the one writer of the file (#52): {@link #save} returns at once and one background thread writes, so no
 * file I/O happens on the JavaFX thread; quick saves in a row (a touchpad zooming with zoom lock on) end in one
 * write of the newest values. {@link #close} writes what is still pending when the app closes.
 */
public final class SettingsStore {

    /** Turns the text of one key into a value and hands it to a setter; throws for text that isn't valid. */
    @FunctionalInterface
    private interface Apply {
        void accept(String text);
    }

    private final Path file;
    private final Consumer<String> warn;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "linview-settings");
        thread.setDaemon(true);   // never keeps the app running; close() writes what's pending
        return thread;
    });
    /** The newest file text not written yet; {@code null} when nothing is waiting. */
    private final AtomicReference<String> pending = new AtomicReference<>();
    private volatile boolean lastSaved = true;
    /** Files written, for tests. */
    final AtomicInteger writes = new AtomicInteger();

    /**
     * {@code warn} receives each problem: an unreadable file, an invalid value or a failed save. A failed save is
     * reported from the writer thread.
     */
    public SettingsStore(Path file, Consumer<String> warn) {
        this.file = file;
        this.warn = warn;
    }

    /** {@code $XDG_CONFIG_HOME/linview/settings.properties}, or under {@code ~/.config} when that isn't set. */
    public static Path defaultFile() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path config = xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".config");
        return config.resolve("linview").resolve("settings.properties");
    }

    /** Reads the file; each problem is passed to {@code warn} and that value keeps its default. */
    public Settings load() {
        Settings s = new Settings();
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            p.load(reader);
        } catch (NoSuchFileException e) {
            return s;
        } catch (IOException | IllegalArgumentException e) {
            warn.accept("Can't read " + file + " (" + e.getMessage() + "); using the defaults");
            return s;
        }

        read(p, "zoom.mode", name(s.zoomMode()), text -> s.setZoomMode(choice(Zoom.Mode.class, text)));
        read(p, "zoom.percent", format(s.zoomPercent()), text -> s.setZoomPercent(Double.parseDouble(text)));
        read(p, "wheel", name(s.wheel()), text -> s.setWheel(choice(Settings.Wheel.class, text)));
        read(p, "background", s.background(), s::setBackground);
        read(p, "overlay.counter", String.valueOf(s.counterOverlay()), text -> s.setCounterOverlay(flag(text)));
        read(p, "overlay.fileName", String.valueOf(s.fileNameOverlay()), text -> s.setFileNameOverlay(flag(text)));
        read(p, "overlay.corner", name(s.overlayCorner()),
                text -> s.setOverlayCorner(choice(Settings.Corner.class, text)));
        read(p, "start.fullScreen", String.valueOf(s.fullScreen()), text -> s.setFullScreen(flag(text)));

        // A position needs both x and y; with only one, the window opens on the mouse's monitor.
        Double[] position = new Double[2];
        read(p, "window.x", "none", text -> position[0] = optionalNumber(text));
        read(p, "window.y", "none", text -> position[1] = optionalNumber(text));
        if (position[0] != null && position[1] != null) {
            s.setWindowPosition(position[0], position[1]);
        }
        read(p, "window.width", format(s.windowWidth()), text -> s.setWindowWidth(Double.parseDouble(text)));
        read(p, "window.height", format(s.windowHeight()), text -> s.setWindowHeight(Double.parseDouble(text)));
        return s;
    }

    /**
     * Saves every setting in the background. The values are copied now, so later changes don't leak into this
     * save. If an earlier save is still waiting, this one replaces it: only the newest values are written.
     */
    public void save(Settings settings) {
        if (pending.getAndSet(text(settings)) == null) {
            writer.execute(this::writePending);
        }
    }

    /**
     * Waits until every save handed in so far is written.
     *
     * @return whether the last write succeeded (true when nothing was saved)
     */
    public boolean flush() {
        try {
            writer.submit(() -> { }).get();   // one thread: everything before it has run
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        }
        return lastSaved;
    }

    /** Writes what is still pending and stops the writer. Called when the app closes. */
    public void close() {
        flush();
        writer.shutdown();
    }

    /**
     * Writes the newest text, creating the folder. The file is written next to it first and then moved into place,
     * so a failed save leaves the old settings intact. A failure is passed to {@code warn}; the app keeps running.
     */
    private void writePending() {
        synchronized (this) {   // tests hold this lock to make saves pile up
            String text = pending.getAndSet(null);   // the newest, taken only when it can be written
            if (text == null) {
                return;
            }
            try {
                write(text);
                lastSaved = true;
            } catch (IOException e) {
                lastSaved = false;
                warn.accept("Can't save the settings to " + file + ": " + e.getMessage());
            }
            writes.incrementAndGet();
        }
    }

    private static String text(Settings s) {
        return String.join("\n",
                "# linview settings. Edit while linview is closed; invalid values fall back to the default.",
                "zoom.mode=" + name(s.zoomMode()),
                "zoom.percent=" + format(s.zoomPercent()),
                "wheel=" + name(s.wheel()),
                "background=" + s.background(),
                "overlay.counter=" + s.counterOverlay(),
                "overlay.fileName=" + s.fileNameOverlay(),
                "overlay.corner=" + name(s.overlayCorner()),
                "start.fullScreen=" + s.fullScreen(),
                "window.x=" + (s.windowX() == null ? "" : format(s.windowX())),
                "window.y=" + (s.windowY() == null ? "" : format(s.windowY())),
                "window.width=" + format(s.windowWidth()),
                "window.height=" + format(s.windowHeight()),
                "");
    }

    private void write(String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "settings", ".tmp");
        try {
            Files.writeString(temp, text);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Applies {@code key} if the file has it; invalid text keeps {@code current} and warns. */
    private void read(Properties p, String key, String current, Apply apply) {
        String value = p.getProperty(key);
        if (value == null) {
            return;
        }
        try {
            apply.accept(value.trim());
        } catch (IllegalArgumentException e) {   // also NumberFormatException
            warn.accept("Invalid value for " + key + ": \"" + value + "\"; using " + current);
        }
    }

    private static <E extends Enum<E>> E choice(Class<E> type, String text) {
        return Enum.valueOf(type, text.toUpperCase(Locale.ROOT).replace('-', '_'));   // bottom-left → BOTTOM_LEFT
    }

    private static boolean flag(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("not true or false: " + text);
        };
    }

    /** An empty value means "not saved yet". */
    private static Double optionalNumber(String text) {
        if (text.isEmpty()) {
            return null;
        }
        double number = Double.parseDouble(text);
        if (!Double.isFinite(number)) {
            throw new IllegalArgumentException("not a finite number: " + text);
        }
        return number;
    }

    private static String name(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** {@code 100} rather than {@code 100.0}; decimals are kept when there are any. */
    private static String format(double number) {
        return number == Math.rint(number) ? String.valueOf((long) number) : String.valueOf(number);
    }
}
