package linview;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The settings file {@code ~/.config/linview/settings.properties} (R-SET-01). A missing file gives the defaults;
 * an unreadable file or an invalid value gives the default plus a warning, never a crash. No UI, so it can be
 * tested without a screen. Keys are documented in README.md.
 */
final class Settings {

    enum ZoomMode { FIT, WIDTH, HEIGHT, ACTUAL, LOCK }

    enum Wheel { SCROLL, NAVIGATE }

    static final List<String> BACKGROUND_PRESETS = List.of("black", "darkgrey", "grey", "white");
    private static final Pattern HEX_COLOUR = Pattern.compile("#[0-9a-fA-F]{6}");

    static final double MIN_PERCENT = 1;
    static final double MAX_PERCENT = 3200;
    /** Smallest window that is still usable; anything smaller in the file is ignored. */
    static final int MIN_WINDOW = 200;

    ZoomMode zoomMode = ZoomMode.FIT;
    /** The locked zoom %, used when {@link #zoomMode} is {@link ZoomMode#LOCK} (R-ZOOM-02). */
    double zoomPercent = 100;
    boolean enlargeSmall = false;
    Wheel wheel = Wheel.SCROLL;
    /** A preset from {@link #BACKGROUND_PRESETS} or {@code #RRGGBB} (R-FS-06). */
    String background = "black";
    boolean fileNameOverlay = true;
    /** Start mode "last used" (R-FS-02): full-screen if the app was closed in full-screen. */
    boolean fullScreen = false;
    /** Window position; {@code null} until saved, then the window opens on the mouse's monitor (R-FS-01). */
    Double windowX = null;
    Double windowY = null;
    double windowWidth = 1024;
    double windowHeight = 700;

    /** {@code $XDG_CONFIG_HOME/linview/settings.properties}, or under {@code ~/.config} when that isn't set. */
    static Path defaultFile() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path config = xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()
                ? Path.of(xdg)
                : Path.of(System.getProperty("user.home"), ".config");
        return config.resolve("linview").resolve("settings.properties");
    }

    /** Reads {@code file}; each problem is passed to {@code warn} and that value keeps its default. */
    static Settings load(Path file, Consumer<String> warn) {
        Settings settings = new Settings();
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            p.load(reader);
        } catch (NoSuchFileException e) {
            return settings;
        } catch (IOException | IllegalArgumentException e) {
            warn.accept("Can't read " + file + " (" + e.getMessage() + "); using the defaults");
            return settings;
        }

        settings.zoomMode = choice(p, "zoom.mode", ZoomMode.class, settings.zoomMode, warn);
        settings.zoomPercent = number(p, "zoom.percent", MIN_PERCENT, MAX_PERCENT, settings.zoomPercent, warn);
        settings.enlargeSmall = flag(p, "zoom.enlargeSmall", settings.enlargeSmall, warn);
        settings.wheel = choice(p, "wheel", Wheel.class, settings.wheel, warn);
        settings.fileNameOverlay = flag(p, "overlay.fileName", settings.fileNameOverlay, warn);
        settings.fullScreen = flag(p, "start.fullScreen", settings.fullScreen, warn);

        String background = p.getProperty("background");
        if (background != null) {
            String value = background.trim().toLowerCase(Locale.ROOT);
            if (BACKGROUND_PRESETS.contains(value) || HEX_COLOUR.matcher(value).matches()) {
                settings.background = value;
            } else {
                invalid(warn, "background", background, settings.background);
            }
        }

        // A position needs both x and y; with only one, the window opens on the mouse's monitor.
        settings.windowX = optionalNumber(p, "window.x", warn);
        settings.windowY = optionalNumber(p, "window.y", warn);
        if (settings.windowX == null || settings.windowY == null) {
            settings.windowX = null;
            settings.windowY = null;
        }
        settings.windowWidth = number(p, "window.width", MIN_WINDOW, 100_000, settings.windowWidth, warn);
        settings.windowHeight = number(p, "window.height", MIN_WINDOW, 100_000, settings.windowHeight, warn);
        return settings;
    }

    /**
     * Writes every setting to {@code file}, creating its folder. The file is written next to it first and then
     * moved into place, so a failed save leaves the old settings intact.
     */
    void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        String text = String.join("\n",
                "# linview settings. Edit while linview is closed; invalid values fall back to the default.",
                "zoom.mode=" + name(zoomMode),
                "zoom.percent=" + format(zoomPercent),
                "zoom.enlargeSmall=" + enlargeSmall,
                "wheel=" + name(wheel),
                "background=" + background,
                "overlay.fileName=" + fileNameOverlay,
                "start.fullScreen=" + fullScreen,
                "window.x=" + (windowX == null ? "" : format(windowX)),
                "window.y=" + (windowY == null ? "" : format(windowY)),
                "window.width=" + format(windowWidth),
                "window.height=" + format(windowHeight),
                "");
        Path temp = Files.createTempFile(file.getParent(), "settings", ".tmp");
        try {
            Files.writeString(temp, text);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static <E extends Enum<E>> E choice(Properties p, String key, Class<E> type, E fallback,
                                               Consumer<String> warn) {
        String value = p.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            invalid(warn, key, value, name(fallback));
            return fallback;
        }
    }

    private static double number(Properties p, String key, double min, double max, double fallback,
                                 Consumer<String> warn) {
        String value = p.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            double number = Double.parseDouble(value.trim());
            if (number >= min && number <= max) {
                return number;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        invalid(warn, key, value, format(fallback));
        return fallback;
    }

    /** An empty or missing value means "not saved yet". */
    private static Double optionalNumber(Properties p, String key, Consumer<String> warn) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            double number = Double.parseDouble(value.trim());
            if (Double.isFinite(number)) {
                return number;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        invalid(warn, key, value, "none");
        return null;
    }

    private static boolean flag(Properties p, String key, boolean fallback, Consumer<String> warn) {
        String value = p.getProperty(key);
        if (value == null) {
            return fallback;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> {
                invalid(warn, key, value, String.valueOf(fallback));
                yield fallback;
            }
        };
    }

    private static void invalid(Consumer<String> warn, String key, String value, String fallback) {
        warn.accept("Invalid value for " + key + ": \"" + value + "\"; using " + fallback);
    }

    private static String name(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** {@code 100} rather than {@code 100.0}; decimals are kept when there are any. */
    private static String format(double number) {
        return number == Math.rint(number) ? String.valueOf((long) number) : String.valueOf(number);
    }
}
