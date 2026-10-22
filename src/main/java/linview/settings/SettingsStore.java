package linview.settings;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Consumer;

import linview.viewer.Zoom;

/**
 * The settings file {@code ~/.config/linview/settings.properties} (R-SET-01). A missing file gives the defaults;
 * an unreadable file or an invalid value gives the default plus a warning, never a crash. The rules for a valid
 * value are the {@link Settings} setters; this class only turns text into values and back. No UI, so it can be
 * tested without a screen. Keys are documented in README.md.
 */
public final class SettingsStore {

    /** Turns the text of one key into a value and hands it to a setter; throws for text that isn't valid. */
    @FunctionalInterface
    private interface Apply {
        void accept(String text);
    }

    private final Path file;
    private final Consumer<String> warn;

    /** {@code warn} receives each problem: an unreadable file, an invalid value or a failed save. */
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
        read(p, "overlay.fileName", String.valueOf(s.fileNameOverlay()), text -> s.setFileNameOverlay(flag(text)));
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
     * Writes every setting, creating the folder. The file is written next to it first and then moved into place,
     * so a failed save leaves the old settings intact. A failure is passed to {@code warn}; the app keeps running.
     *
     * @return whether the settings were saved
     */
    public boolean save(Settings settings) {
        try {
            write(settings);
            return true;
        } catch (IOException e) {
            warn.accept("Can't save the settings to " + file + ": " + e.getMessage());
            return false;
        }
    }

    private void write(Settings s) throws IOException {
        Files.createDirectories(file.getParent());
        String text = String.join("\n",
                "# linview settings. Edit while linview is closed; invalid values fall back to the default.",
                "zoom.mode=" + name(s.zoomMode()),
                "zoom.percent=" + format(s.zoomPercent()),
                "wheel=" + name(s.wheel()),
                "background=" + s.background(),
                "overlay.fileName=" + s.fileNameOverlay(),
                "start.fullScreen=" + s.fullScreen(),
                "window.x=" + (s.windowX() == null ? "" : format(s.windowX())),
                "window.y=" + (s.windowY() == null ? "" : format(s.windowY())),
                "window.width=" + format(s.windowWidth()),
                "window.height=" + format(s.windowHeight()),
                "");
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
        return Enum.valueOf(type, text.toUpperCase(Locale.ROOT));
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
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** {@code 100} rather than {@code 100.0}; decimals are kept when there are any. */
    private static String format(double number) {
        return number == Math.rint(number) ? String.valueOf((long) number) : String.valueOf(number);
    }
}
