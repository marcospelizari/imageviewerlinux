package linview.settings;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import linview.viewer.Zoom;

/**
 * The remembered preferences and their defaults (R-SET-01). Every setter checks its value and throws
 * {@link IllegalArgumentException} for an invalid one, so the settings file and the app share the same rules.
 * {@link SettingsStore} reads and saves them; the controller changes them and saves.
 */
public final class Settings {

    public enum Wheel { SCROLL, NAVIGATE }

    /** Where the full-screen overlay goes (#45); {@code bottom-left} in the file. */
    public enum Corner { BOTTOM_LEFT, BOTTOM_RIGHT, TOP_LEFT, TOP_RIGHT }

    static final List<String> BACKGROUND_PRESETS = List.of("black", "darkgrey", "grey", "white");
    private static final Pattern HEX_COLOUR = Pattern.compile("#[0-9a-f]{6}");

    /** Smallest window that is still usable. */
    public static final int MIN_WINDOW = 200;
    static final int MAX_WINDOW = 100_000;

    private Zoom.Mode zoomMode = Zoom.Mode.FIT;
    private double zoomPercent = 100;
    private Wheel wheel = Wheel.SCROLL;
    private String background = "black";
    private boolean fileNameOverlay = false;
    private boolean counterOverlay = true;
    private Corner overlayCorner = Corner.BOTTOM_LEFT;
    private boolean fullScreen = false;
    private Double windowX = null;
    private Double windowY = null;
    private double windowWidth = 1024;
    private double windowHeight = 700;

    public Zoom.Mode zoomMode() {
        return zoomMode;
    }

    public void setZoomMode(Zoom.Mode mode) {
        zoomMode = require(mode, "zoom mode");
    }

    /** The locked zoom %, used by {@link Zoom.Mode#LOCK} (R-ZOOM-02). */
    public double zoomPercent() {
        return zoomPercent;
    }

    /** {@link Zoom#MIN_PERCENT}–{@link Zoom#MAX_PERCENT}. */
    public void setZoomPercent(double percent) {
        if (!(percent >= Zoom.MIN_PERCENT && percent <= Zoom.MAX_PERCENT)) {
            throw new IllegalArgumentException(
                    "zoom % must be " + Zoom.MIN_PERCENT + "–" + Zoom.MAX_PERCENT + ": " + percent);
        }
        zoomPercent = percent;
    }

    public Wheel wheel() {
        return wheel;
    }

    public void setWheel(Wheel wheel) {
        this.wheel = require(wheel, "wheel");
    }

    /** A preset from {@link #BACKGROUND_PRESETS} or {@code #rrggbb}, in lower case (R-FS-06). */
    public String background() {
        return background;
    }

    /** {@link #background()} as {@code #rrggbb}: the presets are black, #333333, #808080 and white (#31). */
    public String backgroundColour() {
        return switch (background) {
            case "black" -> "#000000";
            case "darkgrey" -> "#333333";
            case "grey" -> "#808080";
            case "white" -> "#ffffff";
            default -> background;
        };
    }

    /** Accepts any case and surrounding spaces; stored in lower case. */
    public void setBackground(String colour) {
        String value = require(colour, "background").trim().toLowerCase(Locale.ROOT);
        if (!BACKGROUND_PRESETS.contains(value) && !HEX_COLOUR.matcher(value).matches()) {
            throw new IllegalArgumentException("background must be a preset or #RRGGBB: " + colour);
        }
        background = value;
    }

    public boolean fileNameOverlay() {
        return fileNameOverlay;
    }

    public void setFileNameOverlay(boolean show) {
        fileNameOverlay = show;
    }

    /** The position {@code 2/16} in the full-screen overlay (R-VIEW-04, #45), on by default. */
    public boolean counterOverlay() {
        return counterOverlay;
    }

    public void setCounterOverlay(boolean show) {
        counterOverlay = show;
    }

    public Corner overlayCorner() {
        return overlayCorner;
    }

    public void setOverlayCorner(Corner corner) {
        overlayCorner = require(corner, "overlay corner");
    }

    /** Start mode "last used" (R-FS-02): full-screen if the app was closed in full-screen. */
    public boolean fullScreen() {
        return fullScreen;
    }

    public void setFullScreen(boolean fullScreen) {
        this.fullScreen = fullScreen;
    }

    /** Window position; {@code null} until saved, then the window opens on the mouse's monitor (R-FS-01). */
    public Double windowX() {
        return windowX;
    }

    public Double windowY() {
        return windowY;
    }

    /** Both or neither: a position needs x and y. */
    public void setWindowPosition(Double x, Double y) {
        if ((x == null) != (y == null)
                || x != null && !(Double.isFinite(x) && Double.isFinite(y))) {
            throw new IllegalArgumentException("window position needs a finite x and y: " + x + ", " + y);
        }
        windowX = x;
        windowY = y;
    }

    public double windowWidth() {
        return windowWidth;
    }

    public void setWindowWidth(double width) {
        windowWidth = windowSide(width, "width");
    }

    public double windowHeight() {
        return windowHeight;
    }

    public void setWindowHeight(double height) {
        windowHeight = windowSide(height, "height");
    }

    private static double windowSide(double size, String name) {
        if (!(size >= MIN_WINDOW && size <= MAX_WINDOW)) {
            throw new IllegalArgumentException(
                    "window " + name + " must be " + MIN_WINDOW + "–" + MAX_WINDOW + ": " + size);
        }
        return size;
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is missing");
        }
        return value;
    }
}
