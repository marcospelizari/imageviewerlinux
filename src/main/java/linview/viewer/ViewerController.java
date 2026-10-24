package linview.viewer;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.function.Consumer;

import linview.image.Loaded;
import linview.image.Orientation;
import linview.settings.Settings;
import linview.settings.SettingsStore;

/**
 * The viewing workflow (REPORT.md §6): opening, navigating, loading and zooming, with the session state they
 * share. Keys and, later, toolbar buttons call these operations; the window only renders what {@link Display}
 * receives. No JavaFX, so it can be tested without a screen. All calls happen on the JavaFX thread.
 *
 * @param <I> the image type: JavaFX {@code Image} in the app
 */
public final class ViewerController<I> {

    public static final String TITLE = "linview";

    public static final String OPEN_HINT = "Press Ctrl+O to open an image or folder";

    /** One wheel notch scrolls this part of the window height (decided 2026-10-22). */
    static final double SCROLL_STEP = 0.1;

    /** Rounding slack before a screen-size copy counts as too small for the zoom (#22). */
    static final double FULL_RESOLUTION_SLACK = 1.001;

    /**
     * Loads image files: large enough for the screen and with at least {@code minResolution} decoded pixels per real
     * pixel (1 = the full image), so the result may be a smaller copy ({@link Loaded#resolution()} below 1). Every
     * implementation (the JavaFX loader, test fakes, later the cache) keeps this
     * contract:
     * <ul>
     *   <li>{@code done} is called at most once, on the JavaFX thread, with the image or {@link Loaded#failed()};</li>
     *   <li>it may be called before {@code load} returns, e.g. when the image is already in memory;</li>
     *   <li>the returned {@code Runnable} cancels as far as possible: a cancelled load may still call {@code done},
     *       or never call it. The controller ignores any result for an image no longer wanted.</li>
     * </ul>
     */
    @FunctionalInterface
    public interface ImageSource<I> {
        Runnable load(Path file, double minResolution, Consumer<Loaded<I>> done);
    }

    /** What the window shows. */
    public interface Display<I> {
        /**
         * {@code image} turned upright with its orientation, drawn at its real size times the view's scale, as
         * {@code view} describes; a smaller decoded copy is stretched to that size.
         */
        void showImage(Loaded<I> image, Zoom.View view);

        void showMessage(String text);

        void showTitle(String title);
    }

    /**
     * {@code name — n/N — W×H — zoom% — linview}, with {@code (locked)} after the zoom while zoom lock is on;
     * size and zoom are left out while they aren't known yet ({@code width <= 0}, {@code scale <= 0}).
     */
    static String title(String name, int position, int size, double width, double height, double scale,
                        boolean locked) {
        String dimensions = width > 0 ? String.format(" — %.0f×%.0f", width, height) : "";
        String zoom = scale > 0 ? " — " + Math.round(scale * 100) + "%" + (locked ? " (locked)" : "") : "";
        return name + " — " + position + "/" + size + dimensions + zoom + " — " + TITLE;
    }

    private final Settings settings;
    private final SettingsStore store;
    private final ImageSource<I> source;
    private final Display<I> display;
    private final TimingLog timing;

    /** The open folder; {@code null} when nothing is open. */
    private ImageList images;
    /** Identity of the latest load: a result for an earlier one is ignored, even if it can't be cancelled. */
    private long request;
    private Runnable cancel = () -> { };
    /** The full-resolution load of the current image (#22), asked for at most once per image. */
    private Runnable cancelFull = () -> { };
    private boolean fullRequested;
    /** The image on screen, and the load it came from; {@code null} when a message is shown. */
    private Loaded<I> shown;
    private long shownRequest;
    private Zoom.View view;
    /** True after + / −: a resize keeps that zoom; the next image starts from the mode again (R-ZOOM-01). */
    private boolean zoomedByHand;
    /** True after a drag: a resize keeps the place, not only the start position (R-ZOOM-05). */
    private boolean pannedByHand;
    /** Wheel movement not yet a whole notch: a touchpad sends many small ones. */
    private double wheelRest;
    /** Whether {@link #wheelRest} came from Ctrl + wheel zoom rather than navigating. */
    private boolean wheelRestIsZoom;
    /** Where Ctrl+Shift+L returns to; fit after a restart with the lock on. */
    private Zoom.Mode modeBeforeLock = Zoom.Mode.FIT;
    private double viewWidth;
    private double viewHeight;
    /** When the current image was asked for, and whether it still has to be logged as shown (#23). */
    private long requestStart;
    private boolean timingPending;

    public ViewerController(Settings settings, SettingsStore store, ImageSource<I> source, Display<I> display,
                            TimingLog timing) {
        this.settings = settings;
        this.store = store;
        this.source = source;
        this.display = display;
        this.timing = timing;
    }

    /** Opens a folder, or an image and its folder (R-NAV-01); problems are shown as a message. */
    public void open(Path path) {
        try {
            images = ImageList.open(path);
        } catch (NoSuchFileException e) {
            nothingOpen("Not found: " + path);
            return;
        } catch (AccessDeniedException e) {
            nothingOpen("No permission to read: " + path);
            return;
        } catch (IOException e) {
            nothingOpen(e.getMessage());
            return;
        }
        if (images.isEmpty()) {
            nothingOpen("No images in " + images.folder() + "\n\n" + OPEN_HINT);
            return;
        }
        showCurrent();
    }

    /** At start without a file, or after the Open dialog was cancelled with nothing open. */
    public void showOpenHintIfEmpty() {
        if (images == null || images.isEmpty()) {
            display.showMessage(OPEN_HINT);
        }
    }

    /** Where the Open dialog starts: the open folder, else ~/Pictures, else home. */
    public Path startFolder() {
        if (images != null && Files.isDirectory(images.folder())) {
            return images.folder();
        }
        Path home = Path.of(System.getProperty("user.home"));
        Path pictures = home.resolve("Pictures");
        return Files.isDirectory(pictures) ? pictures : home;
    }

    public void next() {
        moveTo(images == null ? null : images.next());
    }

    public void previous() {
        moveTo(images == null ? null : images.previous());
    }

    public void first() {
        moveTo(images == null ? null : images.first());
    }

    public void last() {
        moveTo(images == null ? null : images.last());
    }

    /** Pressing the current mode's key again drops a + / − zoom; a new mode is saved (R-SET-01). */
    public void setZoomMode(Zoom.Mode mode) {
        boolean changed = settings.zoomMode() != mode;
        settings.setZoomMode(mode);
        applyMode();
        if (changed) {
            store.save(settings);
        }
    }

    /** A: fit to window ↔ actual size; from any other mode to actual size. */
    public void toggleFitOrActual() {
        setZoomMode(settings.zoomMode() == Zoom.Mode.ACTUAL ? Zoom.Mode.FIT : Zoom.Mode.ACTUAL);
    }

    /**
     * Ctrl+Shift+L (R-ZOOM-02): locks the zoom of the image on screen, so every next image opens at the same %;
     * pressed again, returns to the mode before. The lock is saved. Nothing to lock while no image is shown.
     */
    public void toggleZoomLock() {
        if (settings.zoomMode() == Zoom.Mode.LOCK) {
            setZoomMode(modeBeforeLock);
            return;
        }
        if (view == null || !canLayOut()) {
            return;
        }
        modeBeforeLock = settings.zoomMode();
        double percent = Math.max(Zoom.MIN_PERCENT, Math.min(Zoom.MAX_PERCENT, percentOf(view.scale())));
        settings.setZoomPercent(percent);
        settings.setZoomMode(Zoom.Mode.LOCK);
        store.save(settings);
        zoomTo(percent / 100, viewWidth / 2, viewHeight / 2);
    }

    /** + / −, around the middle of the window. */
    public void zoomIn() {
        zoomInAt(viewWidth / 2, viewHeight / 2);
    }

    public void zoomOut() {
        zoomOutAt(viewWidth / 2, viewHeight / 2);
    }

    /**
     * One zoom step keeping the point at ({@code x}, {@code y}) in place, e.g. under the mouse (R-ZOOM-05).
     * Without zoom lock only the image on screen changes; with it, the locked % changes too and is saved.
     */
    public void zoomInAt(double x, double y) {
        zoomTo(view == null ? 0 : Zoom.zoomIn(view.scale()), x, y);
        saveLockedZoom();
    }

    public void zoomOutAt(double x, double y) {
        zoomTo(view == null ? 0 : Zoom.zoomOut(view.scale()), x, y);
        saveLockedZoom();
    }

    /** R: turns the image on screen 90° clockwise (R-VIEW-01). */
    public void rotateRight() {
        rotate(1);
    }

    /** L: 90° anticlockwise. */
    public void rotateLeft() {
        rotate(-1);
    }

    /**
     * In the view only, the file is not changed. The zoom starts again from the mode, at the top; the next image
     * opens as its EXIF says. While the next image is still loading nothing happens: the image on screen is no
     * longer the selected one (review R7).
     */
    private void rotate(int quarterTurns) {
        if (shown == null || shownRequest != request) {
            return;
        }
        shown = shown.rotated(quarterTurns);
        applyMode();
    }

    /** Drag (R-ZOOM-05): moves the image by ({@code dx}, {@code dy}), only where it's larger than the window. */
    public void pan(double dx, double dy) {
        if (view == null || !canLayOut()) {
            return;
        }
        Zoom.View moved = Zoom.around(new Zoom.View(view.scale(), view.x() + dx, view.y() + dy), view.scale(),
                0, 0, shown.uprightWidth(), shown.uprightHeight(), viewWidth, viewHeight);
        if (!moved.equals(view)) {   // nothing to move: keep the start position for a resize
            pannedByHand = true;
            place(moved);
        }
    }

    /**
     * The wheel without Ctrl (R-WHEEL-01), in notches; positive = wheel up / left. {@code scroll} moves the image
     * and stops at its edges; {@code navigate} goes to the previous (up) or next (down) image.
     */
    public void wheel(double notchesX, double notchesY) {
        if (settings.wheel() == Settings.Wheel.NAVIGATE) {
            int whole = wholeNotches(notchesY, false);
            for (int i = 0; i < Math.abs(whole); i++) {
                if (whole > 0) {
                    previous();
                } else {
                    next();
                }
            }
            return;
        }
        double step = viewHeight * SCROLL_STEP;
        pan(notchesX * step, notchesY * step);
    }

    /** Ctrl + wheel (R-ZOOM-05): one zoom step per notch at ({@code x}, {@code y}); positive = in. */
    public void zoomWheel(double notches, double x, double y) {
        int whole = wholeNotches(notches, true);
        for (int i = 0; i < Math.abs(whole); i++) {
            if (whole > 0) {
                zoomInAt(x, y);
            } else {
                zoomOutAt(x, y);
            }
        }
    }

    /**
     * Adds {@code notches} to what's left over and takes out the whole ones. Turning back, or switching between
     * zoom and navigating (#47), drops the rest.
     */
    private int wholeNotches(double notches, boolean zoom) {
        if (wheelRest * notches < 0 || zoom != wheelRestIsZoom) {
            wheelRest = 0;
            wheelRestIsZoom = zoom;
        }
        wheelRest += notches;
        int whole = (int) wheelRest;
        wheelRest -= whole;
        return whole;
    }

    /**
     * The window's image area changed size: fit modes follow it, a + / − zoom is kept. After a zoom or a drag the
     * point in the middle of the window stays in the middle (decided 2026-10-22); otherwise the image starts again
     * from the mode, at its top.
     */
    public void resize(double width, double height) {
        double oldWidth = viewWidth;
        double oldHeight = viewHeight;
        viewWidth = width;
        viewHeight = height;
        if (view == null || !(zoomedByHand || pannedByHand)) {
            applyMode();
            return;
        }
        if (!canLayOut()) {
            return;
        }
        double w = shown.uprightWidth();
        double h = shown.uprightHeight();
        double scale = zoomedByHand ? view.scale()
                : Zoom.scale(settings.zoomMode(), settings.zoomPercent(), w, h, viewWidth, viewHeight);
        // Shift by half the size change so the old middle is at the new middle, then zoom around it.
        Zoom.View centred = new Zoom.View(view.scale(),
                view.x() + (width - oldWidth) / 2, view.y() + (height - oldHeight) / 2);
        place(Zoom.around(centred, scale, width / 2, height / 2, w, h, viewWidth, viewHeight));
    }

    /** Shows {@code moved} unless it's the image already shown (end of the folder, or nothing open). */
    private void moveTo(ImageList moved) {
        if (moved != null && moved != images) {
            images = moved;
            showCurrent();
        }
    }

    private void nothingOpen(String message) {
        images = null;
        request++;
        timingPending = false;
        cancel.run();
        cancelFull.run();
        shown = null;
        view = null;
        display.showTitle(TITLE);
        display.showMessage(message);
    }

    /** Starts loading the current image; the one on screen stays until the new one is ready. */
    private void showCurrent() {
        requestStart = timing.now();
        timingPending = true;
        cancelFull.run();   // the full image of the previous one is no longer wanted
        cancelFull = () -> { };
        fullRequested = false;
        Path file = images.current();
        display.showTitle(title(file.getFileName().toString(), images.position(), images.size(), 0, 0, 0, false));
        // The new request is current before loading starts, so a result that arrives at once is accepted and a
        // late one for the previous image is ignored.
        long id = ++request;
        Runnable previous = cancel;
        cancel = source.load(file, resolutionForMode(), result -> loaded(id, file, result));
        previous.run();   // stop decoding an image no longer wanted (fast key presses)
    }

    private void loaded(long id, Path file, Loaded<I> result) {
        if (id != request) {
            return;
        }
        if (result.isFailed()) {
            timingPending = false;
            timing.failed(file.getFileName().toString(), requestStart);
            shown = null;
            view = null;
            display.showMessage("Can't show " + file.getFileName() + ": the file is damaged or not a supported image");
            return;
        }
        shown = result;
        shownRequest = id;
        applyMode();
    }

    /** Sizes and places the image from the zoom mode in the settings, dropping any + / − zoom or drag. */
    private void applyMode() {
        zoomedByHand = false;
        pannedByHand = false;
        if (!canLayOut()) {
            return;
        }
        double w = shown.uprightWidth();
        double h = shown.uprightHeight();
        double scale = Zoom.scale(settings.zoomMode(), settings.zoomPercent(), w, h, viewWidth, viewHeight);
        place(Zoom.start(scale, w, h, viewWidth, viewHeight));
    }

    /** Keeps the point at ({@code x}, {@code y}) where it is. */
    private void zoomTo(double scale, double x, double y) {
        if (view == null || !canLayOut()) {
            return;
        }
        zoomedByHand = true;
        place(Zoom.around(view, scale, x, y,
                shown.uprightWidth(), shown.uprightHeight(), viewWidth, viewHeight));
    }

    private void saveLockedZoom() {
        if (settings.zoomMode() != Zoom.Mode.LOCK || view == null) {
            return;
        }
        double percent = percentOf(view.scale());
        if (settings.zoomPercent() != percent) {
            settings.setZoomPercent(percent);
            store.save(settings);
        }
    }

    /** Scale as %, to two decimals, so the settings file says 67 rather than 67.00000000000001. */
    private static double percentOf(double scale) {
        return Math.round(scale * 10_000) / 100.0;
    }

    private boolean canLayOut() {
        return shown != null && viewWidth > 0 && viewHeight > 0;
    }

    private void place(Zoom.View v) {
        view = v;
        display.showImage(shown, v);
        // While the next image is still loading, its own title (without size) stays.
        if (shownRequest == request) {
            Path file = images.current();
            display.showTitle(title(file.getFileName().toString(), images.position(), images.size(),
                    shown.uprightWidth(), shown.uprightHeight(), v.scale(), settings.zoomMode() == Zoom.Mode.LOCK));
            if (timingPending) {
                timingPending = false;
                timing.shown(file.getFileName().toString(), requestStart, shown.loadMillis());
            }
            loadFullIfNeeded(file, v);
        }
    }

    /**
     * #22: the zoom asks for more pixels than the screen-size copy on screen has, e.g. actual size or + / −: load the
     * full image, once, for this image only. Until it arrives the copy stays, stretched.
     */
    private void loadFullIfNeeded(Path file, Zoom.View v) {
        if (fullRequested || v.scale() <= shown.resolution() * FULL_RESOLUTION_SLACK) {
            return;
        }
        fullRequested = true;
        long id = request;
        cancelFull = source.load(file, 1, result -> fullLoaded(id, file, result));
    }

    /**
     * What the zoom mode will need from a new image, known before its size is: actual size the full image, zoom lock
     * its %; the fit modes nothing beyond the screen. Asking at once avoids decoding the image twice.
     */
    private double resolutionForMode() {
        return switch (settings.zoomMode()) {
            case ACTUAL -> 1;
            case LOCK -> Math.min(1, settings.zoomPercent() / 100);
            default -> 0;
        };
    }

    /** The full image replaces the copy in the same place; a late one or a failure keeps what is shown. */
    private void fullLoaded(long id, Path file, Loaded<I> result) {
        if (id != request || shownRequest != id || shown == null || result.isFailed()) {
            return;
        }
        shown = shown.withImage(result);
        timing.full(file.getFileName().toString(), result.loadMillis());
        display.showImage(shown, view);
    }
}
