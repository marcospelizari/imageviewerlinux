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

    /**
     * Loads image files. Every implementation (the JavaFX loader, test fakes, later the cache) keeps this contract:
     * <ul>
     *   <li>{@code done} is called at most once, on the JavaFX thread, with the image or {@link Loaded#failed()};</li>
     *   <li>it may be called before {@code load} returns, e.g. when the image is already in memory;</li>
     *   <li>the returned {@code Runnable} cancels as far as possible: a cancelled load may still call {@code done},
     *       or never call it. The controller ignores any result for an image no longer wanted.</li>
     * </ul>
     */
    @FunctionalInterface
    public interface ImageSource<I> {
        Runnable load(Path file, Consumer<Loaded<I>> done);
    }

    /** What the window shows. */
    public interface Display<I> {
        /** {@code image} turned upright with {@code orientation}, drawn as {@code view} describes. */
        void showImage(I image, Orientation orientation, Zoom.View view);

        void showMessage(String text);

        void showTitle(String title);
    }

    /**
     * {@code name — n/N — W×H — zoom% — linview}; size and zoom are left out while they aren't known yet
     * ({@code width <= 0}, {@code scale <= 0}).
     */
    static String title(String name, int position, int size, double width, double height, double scale) {
        String dimensions = width > 0 ? String.format(" — %.0f×%.0f", width, height) : "";
        String zoom = scale > 0 ? " — " + Math.round(scale * 100) + "%" : "";
        return name + " — " + position + "/" + size + dimensions + zoom + " — " + TITLE;
    }

    private final Settings settings;
    private final SettingsStore store;
    private final ImageSource<I> source;
    private final Display<I> display;

    /** The open folder; {@code null} when nothing is open. */
    private ImageList images;
    /** Identity of the latest load: a result for an earlier one is ignored, even if it can't be cancelled. */
    private long request;
    private Runnable cancel = () -> { };
    /** The image on screen, and the load it came from; {@code null} when a message is shown. */
    private Loaded<I> shown;
    private long shownRequest;
    private Zoom.View view;
    /** True after + / −: a resize keeps that zoom; the next image starts from the mode again (R-ZOOM-01). */
    private boolean zoomedByHand;
    private double viewWidth;
    private double viewHeight;

    public ViewerController(Settings settings, SettingsStore store, ImageSource<I> source, Display<I> display) {
        this.settings = settings;
        this.store = store;
        this.source = source;
        this.display = display;
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

    public void zoomIn() {
        zoomTo(view == null ? 0 : Zoom.zoomIn(view.scale()));
    }

    public void zoomOut() {
        zoomTo(view == null ? 0 : Zoom.zoomOut(view.scale()));
    }

    /** The window's image area changed size: fit modes follow it; a + / − zoom is kept and only tidied. */
    public void resize(double width, double height) {
        viewWidth = width;
        viewHeight = height;
        if (!zoomedByHand || view == null) {
            applyMode();
        } else if (canLayOut()) {
            place(Zoom.around(view, view.scale(), 0, 0,
                    shown.uprightWidth(), shown.uprightHeight(), viewWidth, viewHeight));
        }
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
        cancel.run();
        shown = null;
        view = null;
        display.showTitle(TITLE);
        display.showMessage(message);
    }

    /** Starts loading the current image; the one on screen stays until the new one is ready. */
    private void showCurrent() {
        Path file = images.current();
        display.showTitle(title(file.getFileName().toString(), images.position(), images.size(), 0, 0, 0));
        // The new request is current before loading starts, so a result that arrives at once is accepted and a
        // late one for the previous image is ignored.
        long id = ++request;
        Runnable previous = cancel;
        cancel = source.load(file, result -> loaded(id, file, result));
        previous.run();   // stop decoding an image no longer wanted (fast key presses)
    }

    private void loaded(long id, Path file, Loaded<I> result) {
        if (id != request) {
            return;
        }
        if (result.isFailed()) {
            shown = null;
            view = null;
            display.showMessage("Can't show " + file.getFileName() + ": the file is damaged or not a supported image");
            return;
        }
        shown = result;
        shownRequest = id;
        applyMode();
    }

    /** Sizes and places the image from the zoom mode in the settings, dropping any + / − zoom. */
    private void applyMode() {
        zoomedByHand = false;
        if (!canLayOut()) {
            return;
        }
        double w = shown.uprightWidth();
        double h = shown.uprightHeight();
        double scale = Zoom.scale(settings.zoomMode(), settings.zoomPercent(), w, h, viewWidth, viewHeight);
        place(Zoom.start(scale, w, h, viewWidth, viewHeight));
    }

    /** Keeps the point in the middle of the window where it is. */
    private void zoomTo(double scale) {
        if (view == null || !canLayOut()) {
            return;
        }
        zoomedByHand = true;
        place(Zoom.around(view, scale, viewWidth / 2, viewHeight / 2,
                shown.uprightWidth(), shown.uprightHeight(), viewWidth, viewHeight));
    }

    private boolean canLayOut() {
        return shown != null && viewWidth > 0 && viewHeight > 0;
    }

    private void place(Zoom.View v) {
        view = v;
        display.showImage(shown.image(), shown.orientation(), v);
        // While the next image is still loading, its own title (without size) stays.
        if (shownRequest == request) {
            Path file = images.current();
            display.showTitle(title(file.getFileName().toString(), images.position(), images.size(),
                    shown.uprightWidth(), shown.uprightHeight(), v.scale()));
        }
    }
}
