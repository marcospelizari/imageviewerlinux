package linview.viewer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import linview.files.FileActions;
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

    /** Preloading (#21, REPORT.md §3.5): this many images ahead in the direction of movement, and behind. */
    static final int PRELOAD_AHEAD = 3;
    static final int PRELOAD_BEHIND = 1;

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
    public interface ImageSource<I> {
        Runnable load(Path file, double minResolution, Consumer<Loaded<I>> done);

        /**
         * The images likely to come next, most wanted first (#21), each with what the zoom mode needs
         * ({@code minResolution}) as far as memory allows (#55): load them in the background without delaying
         * {@link #load}, and drop others. Called after each {@code load}.
         */
        void preload(List<Path> files, double minResolution);
    }

    /** What the window shows. */
    public interface Display<I> {
        /**
         * {@code image} turned upright with its orientation, drawn at its real size times the view's scale, as
         * {@code view} describes; a smaller decoded copy is stretched to that size.
         */
        void showImage(Loaded<I> image, Zoom.View view);

        /**
         * The full image of the screen-size copy on screen, for the magnifier only (#60): while the copy is enough for
         * the zoom the view keeps it, so a 300 MP image isn't drawn on every frame.
         */
        void showFullImage(I image);

        void showMessage(String text);

        void showTitle(String title);

        /**
         * The open folder's images and which one is selected (0-based), for the thumbnail strip (#28); an empty list
         * when nothing is open. The list is the same object while the folder stays open.
         */
        void showPosition(List<Path> files, int index);

        /**
         * The image's size as shown upright and its zoom %, with the lock state, for the taskbar (#29); a size of 0
         * when no image is shown.
         */
        void showZoom(double width, double height, double percent, boolean locked);

        /** Asks whether to save the turn of the image {@code name} to its file before leaving it (#51). */
        RotationAnswer askSaveRotation(String name);

        /** Says that the turn of {@code name} can't be saved, as its format can't take it (#51): once per run. */
        void showRotationNotSavable(String name);

        /**
         * Saves {@code quarterTurns} × 90° clockwise to {@code file}, which was shown with {@code stored}, its stored
         * size {@code width} × {@code height} (#51): in the background, then {@link #rotationSaved} or a message.
         */
        void saveRotation(Path file, Orientation stored, double width, double height, int quarterTurns);
    }

    /** The answer to "Save rotation?": save or not, and whether to do the same from now on without asking. */
    public record RotationAnswer(boolean save, boolean dontAskAgain) {
    }

    /**
     * {@code name — n/N — W×H — zoom% — linview}, with {@code (locked)} after the zoom while zoom lock is on;
     * size and zoom are left out while they aren't known yet ({@code width <= 0}, {@code scale <= 0}).
     */
    /**
     * The full-screen overlay (R-VIEW-04, R-FS-05, #45): {@code 2/16}, with {@code fileName} {@code a.jpg  2/16};
     * empty when both are off.
     */
    public static String overlayText(String name, int position, int size, boolean counter, boolean fileName) {
        String count = counter ? position + "/" + size : "";
        return fileName && counter ? name + "  " + count : fileName ? name : count;
    }

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
    private final Executor background;
    private final Executor onJavaFXThread;

    /** The open folder; {@code null} when nothing is open. */
    private ImageList images;
    /**
     * Identity of the latest {@link #open}: a folder listed for an earlier one is ignored (#21), and one not started
     * yet is skipped (#55). Read by the listing thread, hence volatile.
     */
    private volatile long opening;
    /** The direction of the last move, which preloading follows (#21). */
    private boolean forward = true;
    /** Identity of the latest load: a result for an earlier one is ignored, even if it can't be cancelled. */
    private long request;
    private Runnable cancel = () -> { };
    /** The full-resolution load of the current image (#22), asked for at most once per image. */
    private Runnable cancelFull = () -> { };
    private boolean fullRequested;
    /** That full image once loaded (#60): for the magnifier, and swapped in when a zoom needs more than the copy. */
    private Loaded<I> full;
    /** The image on screen, and the load it came from; {@code null} when a message is shown. */
    private Loaded<I> shown;
    private long shownRequest;
    /** The note that a format can't keep a turn was shown in this run (#51). */
    private boolean notSavableShown;
    /** The load that failed with the message on screen; a damaged file can still be moved to the Trash (#34). */
    private long failedRequest = -1;
    /** R / L turns of the image on screen, on top of its EXIF orientation: kept if its full image differs (#65). */
    private int turns;
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
    /**
     * #63: an open with zoom lock on fits its first image and locks that %. {@code lockFromFit} waits for the first
     * image shown; {@code lockFitRequest} is its load, which follows the fit (e.g. going full-screen) until left.
     */
    private boolean lockFromFit;
    private long lockFitRequest = -1;
    private double viewWidth;
    private double viewHeight;
    /** When the current image was asked for, and whether it still has to be logged as shown (#23). */
    private long requestStart;
    private boolean timingPending;

    /**
     * {@code background} lists folders off the JavaFX thread (#21); {@code onJavaFXThread} brings the result back
     * ({@code Platform::runLater} in the app, {@code Runnable::run} in tests).
     */
    public ViewerController(Settings settings, SettingsStore store, ImageSource<I> source, Display<I> display,
                            TimingLog timing, Executor background, Executor onJavaFXThread) {
        this.settings = settings;
        this.store = store;
        this.source = source;
        this.display = display;
        this.timing = timing;
        this.background = background;
        this.onJavaFXThread = onJavaFXThread;
    }

    /**
     * Opens a folder, or an image and its folder (R-NAV-01); problems are shown as a message. The folder is listed and
     * sorted in the background (#21); until then the image on screen stays and can still be navigated. A listing for
     * an earlier open is skipped if it hasn't started, and its result ignored if it has (#55): a listing already
     * running on a slow disk can't be interrupted.
     */
    public void open(Path path) {
        leaveImage();
        long id = ++opening;
        background.execute(() -> {
            if (id != opening) {
                return;   // another folder was opened meanwhile
            }
            ImageList list = null;
            String problem = null;
            try {
                list = ImageList.open(path);
            } catch (NoSuchFileException e) {
                problem = "Not found: " + path;
            } catch (AccessDeniedException e) {
                problem = "No permission to read: " + path;
            } catch (IOException | UncheckedIOException e) {   // Files.list throws the unchecked one while listing
                problem = e.getMessage();
            }
            ImageList listed = list;
            String failure = problem;
            onJavaFXThread.execute(() -> opened(id, listed, failure));
        });
    }

    private void opened(long id, ImageList list, String problem) {
        if (id != opening) {
            return;
        }
        if (problem != null) {
            nothingOpen(problem);
            return;
        }
        // The lock stays on; its % comes from the first image fitting the window (Marcos, 2026-10-25, #63).
        lockFromFit = settings.zoomMode() == Zoom.Mode.LOCK;
        images = list;
        forward = true;
        if (images.isEmpty()) {
            nothingOpen("No images in " + images.folder() + "\n\n" + OPEN_HINT);
            return;
        }
        showCurrent();
    }

    /**
     * The window closes (R-FS-02, #26): the next start opens in the same mode, and a window at the same place and
     * size, {@code x}, {@code y} its corner and {@code width} × {@code height} its content, as last seen outside
     * full-screen. Saved now; the store writes it before the app ends.
     */
    public void windowClosed(boolean fullScreen, double x, double y, double width, double height) {
        settings.setFullScreen(fullScreen);
        settings.setWindowPosition(x, y);
        settings.setWindowWidth(Math.max(width, Settings.MIN_WINDOW));
        settings.setWindowHeight(Math.max(height, Settings.MIN_WINDOW));
        store.save(settings);
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
        forward = true;
        moveTo(images == null ? null : images.next());
    }

    public void previous() {
        forward = false;
        moveTo(images == null ? null : images.previous());
    }

    /** From the first image the way on is forward, from the last one backward. */
    /** Goes to the image at {@code index} (0-based), e.g. a thumbnail clicked (#28). */
    public void select(int index) {
        if (images != null) {
            forward = index >= images.position() - 1;
            moveTo(images.at(index));
        }
    }

    public void first() {
        forward = true;
        moveTo(images == null ? null : images.first());
    }

    public void last() {
        forward = false;
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

    /**
     * The open mode chosen in the settings window (#48): like the keys, and Lock like Ctrl+Shift+L, locking the % of
     * the image on screen; with none shown, the next open fits its first image and locks that (#63).
     */
    public void chooseZoomMode(Zoom.Mode mode) {
        if (mode != Zoom.Mode.LOCK) {
            setZoomMode(mode);
        } else if (settings.zoomMode() != Zoom.Mode.LOCK) {
            if (view != null && canLayOut()) {
                toggleZoomLock();
            } else {
                modeBeforeLock = settings.zoomMode();
                settings.setZoomMode(Zoom.Mode.LOCK);
                store.save(settings);
            }
        }
    }

    /** Saves preferences the window changed itself, e.g. in the settings window (#48). */
    public void saveSettings() {
        store.save(settings);
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
    /** A zoom % typed in the taskbar (#29): like + / −, around the middle of the window. */
    public void zoomToPercent(double percent) {
        zoomTo(Math.max(Zoom.MIN_PERCENT, Math.min(Zoom.MAX_PERCENT, percent)) / 100, viewWidth / 2, viewHeight / 2);
        saveLockedZoom();
    }

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
        turns += quarterTurns;
        applyMode();
    }

    /** True while the selected image is on screen, finished loading: what R / L and the lock act on (#49). */
    public boolean isImageShown() {
        return shown != null && shownRequest == request;
    }

    /**
     * The file Del moves to the Trash (R-FILE-01, #34): the selected image once its load has finished, shown or
     * failed. {@code null} while the next image is still loading, because the one on screen is no longer the
     * selected one (review R7), and when nothing is open. The window keeps this path while it asks, so navigating
     * can't change the target.
     */
    public Path trashTarget() {
        if (images == null || images.isEmpty()) {
            return null;
        }
        boolean finished = shown != null && shownRequest == request || failedRequest == request;
        return finished ? images.current() : null;
    }

    /**
     * {@code file} was moved to the Trash: it leaves the folder. If it was the current image the next one is shown,
     * or the previous one when it was the last; after the folder's only image, a message. A file of another folder
     * (opened meanwhile) changes nothing.
     */
    public void trashed(Path file) {
        if (images == null) {
            return;
        }
        boolean wasCurrent = images.current().equals(file);
        ImageList rest = images.without(file);
        if (rest == images) {
            return;
        }
        images = rest;
        if (images.isEmpty()) {
            nothingOpen("No images left in " + images.folder() + "\n\n" + OPEN_HINT);
        } else if (wasCurrent) {
            showCurrent();
        } else {
            // Moved on while the Trash was busy: only the position and the count change.
            display.showPosition(images.files(), images.position() - 1);
            if (view != null && shownRequest == request) {
                place(view);
            } else {
                display.showTitle(title(images.current().getFileName().toString(), images.position(),
                        images.size(), 0, 0, 0, false));
            }
            source.preload(images.upcoming(forward, PRELOAD_AHEAD, PRELOAD_BEHIND), resolutionForMode());
        }
    }

    /**
     * Leaving the image on screen (next, open, quit): a turn made with R / L can be saved to its file (R-FILE-03,
     * #51), as {@code rotation.save} says: asked, always or never. A full turn asks nothing; a format that can't keep
     * it gets a note, once per run. Called by navigation and open; the window calls it before quitting. After it the
     * turn counts as handled, so a later R / L adds to what the file then holds.
     */
    public void leaveImage() {
        int quarterTurns = Math.floorMod(turns, 4);
        if (shown == null || shownRequest != request || quarterTurns == 0
                || settings.rotationSave() == Settings.RotationSave.NEVER) {
            return;
        }
        Path file = images.current();
        String name = file.getFileName().toString();
        Orientation stored = shown.orientation().rotated(-turns);
        turns = 0;
        if (!FileActions.canSaveRotation(file)) {
            if (!notSavableShown) {
                notSavableShown = true;
                display.showRotationNotSavable(name);
            }
            return;
        }
        boolean save = true;
        if (settings.rotationSave() == Settings.RotationSave.ASK) {
            RotationAnswer answer = display.askSaveRotation(name);
            save = answer.save();
            if (answer.dontAskAgain()) {
                settings.setRotationSave(save ? Settings.RotationSave.ALWAYS : Settings.RotationSave.NEVER);
                store.save(settings);
            }
        }
        if (save) {
            display.saveRotation(file, stored, shown.width(), shown.height(), quarterTurns);
        }
    }

    /**
     * The turn was saved to {@code file} (#51). Back on it before the save ended, the old file is on screen: it is
     * loaded again (the cache sees the new change time), unless it has been turned again meanwhile.
     */
    public void rotationSaved(Path file) {
        if (images != null && !images.isEmpty() && images.current().equals(file) && shown != null
                && shownRequest == request && turns == 0) {
            showCurrent();
        }
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
     * ← → ↑ ↓ (#36, FastStone): one wheel notch of scrolling on a side where the image is larger than the window,
     * stopping at its edges. Where it isn't wider, ← → go to the previous / next image; ↑ ↓ then do nothing.
     * {@code dx}, {@code dy}: −1, 0 or 1, positive = right / down.
     */
    public void arrow(int dx, int dy) {
        if (dx != 0 && !wider()) {
            if (dx > 0) {
                next();
            } else {
                previous();
            }
            return;
        }
        double step = viewHeight * SCROLL_STEP;
        pan(-dx * step, -dy * step);
    }

    /**
     * The selected image is on screen and wider than the window, by more than rounding (fit width is not wider).
     * While the next image loads the old one doesn't count, so ← → keep navigating.
     */
    private boolean wider() {
        return view != null && canLayOut() && shownRequest == request
                && shown.uprightWidth() * view.scale() > viewWidth + 0.5;
    }

    /**
     * The wheel without Ctrl or Shift (R-WHEEL-01), in notches; positive = wheel up / left. {@code scroll} moves the image
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
            leaveImage();
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
        source.preload(List.of(), 0);
        shown = null;
        view = null;
        display.showTitle(TITLE);
        display.showPosition(List.of(), -1);
        display.showZoom(0, 0, 0, false);
        display.showMessage(message);
    }

    /** Starts loading the current image; the one on screen stays until the new one is ready. */
    private void showCurrent() {
        requestStart = timing.now();
        timingPending = true;
        cancelFull.run();   // the full image of the previous one is no longer wanted
        cancelFull = () -> { };
        fullRequested = false;
        full = null;
        Path file = images.current();
        display.showTitle(title(file.getFileName().toString(), images.position(), images.size(), 0, 0, 0, false));
        display.showPosition(images.files(), images.position() - 1);
        // The new request is current before loading starts, so a result that arrives at once is accepted and a
        // late one for the previous image is ignored.
        long id = ++request;
        Runnable previous = cancel;
        cancel = source.load(file, resolutionForMode(), result -> loaded(id, file, result));
        previous.run();   // stop decoding an image no longer wanted (fast key presses)
        source.preload(images.upcoming(forward, PRELOAD_AHEAD, PRELOAD_BEHIND), resolutionForMode());
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
            failedRequest = id;
            display.showMessage(result.isTooLarge()
                    ? "Can't show " + file.getFileName() + ": the image is too large (" + result.megapixels() + " MP)"
                    : "Can't show " + file.getFileName() + ": the file is damaged or not a supported image");
            return;
        }
        shown = result;
        shownRequest = id;
        turns = 0;
        if (lockFromFit) {
            lockFromFit = false;
            lockFitRequest = id;
        }
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
        double scale;
        if (settings.zoomMode() == Zoom.Mode.LOCK && shownRequest == lockFitRequest) {
            double percent = Math.max(Zoom.MIN_PERCENT, Math.min(Zoom.MAX_PERCENT,
                    percentOf(Zoom.scale(Zoom.Mode.FIT, 0, w, h, viewWidth, viewHeight))));
            if (settings.zoomPercent() != percent) {
                settings.setZoomPercent(percent);
                store.save(settings);
            }
            scale = percent / 100;
        } else {
            scale = Zoom.scale(settings.zoomMode(), settings.zoomPercent(), w, h, viewWidth, viewHeight);
        }
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
        if (full != null && needsMoreThanShown(v)) {
            shown = shown.withImage(full);   // kept for the magnifier until now (#60)
        }
        display.showImage(shown, v);
        // While the next image is still loading, its own title (without size) stays.
        if (shownRequest == request) {
            Path file = images.current();
            boolean locked = settings.zoomMode() == Zoom.Mode.LOCK;
            display.showTitle(title(file.getFileName().toString(), images.position(), images.size(),
                    shown.uprightWidth(), shown.uprightHeight(), v.scale(), locked));
            display.showZoom(shown.uprightWidth(), shown.uprightHeight(), percentOf(v.scale()), locked);
            if (timingPending) {
                timingPending = false;
                timing.shown(file.getFileName().toString(), requestStart, shown.loadMillis(), shown.cached());
            }
            loadFullIfNeeded(file, v);
        }
    }

    /**
     * #22: the zoom asks for more pixels than the screen-size copy on screen has, e.g. actual size or + / −: load the
     * full image, once, for this image only. Until it arrives the copy stays, stretched.
     */
    private void loadFullIfNeeded(Path file, Zoom.View v) {
        if (needsMoreThanShown(v)) {
            loadFull(file);
        }
    }

    private boolean needsMoreThanShown(Zoom.View v) {
        return v.scale() > shown.resolution() * FULL_RESOLUTION_SLACK;
    }

    /** The magnifier needs every pixel (#32): the full image of a screen-size copy, as a zoom past it would. */
    public void loadFullResolution() {
        if (images != null && shown != null && shownRequest == request && shown.resolution() < 1) {
            loadFull(images.current());
        }
    }

    private void loadFull(Path file) {
        if (fullRequested) {
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
            case LOCK -> lockFromFit ? 0 : Math.min(1, settings.zoomPercent() / 100);   // #63: fits first
            default -> 0;
        };
    }

    /**
     * The full image replaces the copy in the same place when the zoom needs it; else, asked for by the magnifier, it
     * goes to the magnifier only (#60). A late one or a failure keeps what is shown. One with another size or EXIF
     * orientation is from a file replaced since (#65): it is laid out as a new image, with the R / L turns kept.
     */
    private void fullLoaded(long id, Path file, Loaded<I> result) {
        if (id != request || shownRequest != id || shown == null || result.isFailed()) {
            return;
        }
        timing.full(file.getFileName().toString(), result.loadMillis());
        Loaded<I> asLoaded = shown.rotated(-turns);
        if (result.width() != asLoaded.width() || result.height() != asLoaded.height()
                || !result.orientation().equals(asLoaded.orientation())) {
            shown = result.rotated(turns);
            applyMode();
            return;
        }
        full = result;
        if (view != null && needsMoreThanShown(view)) {
            shown = shown.withImage(result);
            display.showImage(shown, view);
        } else {
            display.showFullImage(result.image());
        }
    }
}
