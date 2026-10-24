package linview.ui;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.util.Duration;

import linview.image.ImageResampler;
import linview.image.Loaded;
import linview.image.Orientation;
import linview.viewer.ViewerController;
import linview.viewer.Zoom;

/**
 * Shows one image, or a message instead of it. Where the image goes and how large it is comes from the
 * {@code ViewerController}; this pane only draws it, and passes its size, the wheel and drags on to it (R-ZOOM-05,
 * R-WHEEL-01).
 *
 * <p>The controller works in physical screen pixels, so actual size is one image pixel per screen pixel at any desktop
 * scaling (R-DISP-01, #46); this pane is the one place that converts, with the window's render scale (2 at 200 %
 * scaling). Moving the window to a monitor with other scaling changes it, which counts as a resize.
 *
 * <p>Sharp redraw (R-ZOOM-06, #24): the graphics card's scaling shows at once; when the image is shown smaller than
 * its pixels, {@link #SETTLE} after the last change the whole image is shrunk with Lanczos on a background thread
 * and swapped in, one physical pixel per screen pixel. Any change starts again; a late result for another image or
 * scale is dropped, and a drag keeps the sharp copy. Not for animated GIFs (it would stop them) or images over
 * {@link ImageResampler#MAX_SOURCE_PIXELS}.
 *
 * <p>In full-screen the mouse pointer hides after {@link #POINTER_IDLE} without movement and returns when the mouse
 * moves (R-FS-08, #33).
 *
 * <p>Magnifier (R-FS-07, #32): while the whole image fits the window, holding the left button shows a round loupe
 * at {@link #LOUPE_ZOOM} times actual size under the mouse; when the image is larger, the left button pans instead
 * (Marcos, 2026-10-24). It shows the image on screen, unsmoothed so its real pixels show, and asks for the full
 * image if a screen-size copy is shown (#22). It appears after the button is held {@link #LOUPE_DELAY} or as soon as
 * the mouse moves, so a click doesn't flash it.
 *
 * <p>Clicks (R-NAV-03, #62, as in FastStone): a left click on the right half goes to the next image, on the left half
 * to the previous one; it waits {@link #DOUBLE_CLICK} so a double-click (full-screen) wins. A drag or a hold is
 * not a click.
 */
public final class ViewerPane extends StackPane {

    private final ImageView view = new ImageView();
    /** Holds the turned image, so its layout bounds are the upright size and it can be placed by its corner. */
    private final Group turned = new Group(view);

    static final double LOUPE_RADIUS = 150;
    /** Screen pixels per image pixel in the loupe: 2 = twice actual size. */
    static final double LOUPE_ZOOM = 2;
    private final ImageView lensView = new ImageView();
    private final Group lensTurned = new Group(lensView);
    private final Circle lensClip = new Circle(LOUPE_RADIUS);
    private final Circle lensRing = new Circle(LOUPE_RADIUS);
    /** Covers the pane, so its coordinates are the pane's; clipped to the loupe's circle. */
    private final Pane lens = new Pane(lensTurned);
    /** What is on screen, for the loupe; {@code null} while a message is shown. */
    private Loaded<Image> shownLoaded;
    private Zoom.View shownView;
    private boolean magnifying;
    static final Duration LOUPE_DELAY = Duration.millis(200);
    private final PauseTransition loupeDelay = new PauseTransition(LOUPE_DELAY);
    static final Duration DOUBLE_CLICK = Duration.millis(300);
    private final PauseTransition clickDelay = new PauseTransition(DOUBLE_CLICK);
    private Runnable previous = () -> { };
    private Runnable next = () -> { };
    private Runnable doubleClick = () -> { };
    /** The loupe was shown during this press, so its release isn't a click. */
    private boolean loupeShown;
    private double loupeX;
    private double loupeY;
    private final Label message = new Label();
    private ViewerController<Image> controller;
    /** Physical pixels per JavaFX pixel. */
    private double scale = 1;

    static final Duration SETTLE = Duration.millis(150);
    private final PauseTransition settle = new PauseTransition(SETTLE);
    private final ExecutorService resampler = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "linview-resampler");
        thread.setDaemon(true);
        return thread;
    });
    /** Counts redraws; one whose number is no longer this is stale. Read by the resampler thread. */
    private volatile long redraw;
    /** The sharp copy on screen, the image it was made from and the zoom it is for; {@code null} when none. */
    private Image sharp;
    private Image sharpSource;
    private double sharpScale;
    /** The image is larger than the pane on some side, so a drag moves it. */
    private boolean pannable;
    private boolean dragging;

    static final Duration POINTER_IDLE = Duration.seconds(2);
    private final PauseTransition idle = new PauseTransition(POINTER_IDLE);
    private boolean pointerHidden;
    private boolean fullScreen;
    /** A full-screen panel is open (#27): the pointer stays. */
    private boolean pointerKept;
    private double dragX;
    private double dragY;

    public ViewerPane() {
        setBackgroundColour("#000000");
        view.setSmooth(true);
        message.setWrapText(true);
        turned.setManaged(false);   // placed by hand; the pane must not centre it or grow to the image's size
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
        lensView.setSmooth(false);
        lensTurned.setManaged(false);
        lens.setClip(lensClip);
        lensRing.setManaged(false);
        lensRing.setMouseTransparent(true);
        lensRing.setVisible(false);
        lensRing.setFill(null);
        lensRing.setStroke(Color.web("#ffffff", 0.8));
        lensRing.setStrokeWidth(2);
        lensRing.setEffect(new DropShadow(6, Color.BLACK));
        lens.setMouseTransparent(true);
        lens.setVisible(false);
        getChildren().addAll(turned, lens, lensRing, message);

        setOnScroll(this::scrolled);
        setOnMousePressed(e -> {
            loupeShown = false;
            if (e.getButton() == MouseButton.PRIMARY && pannable) {
                dragging = true;
                dragX = e.getX();
                dragY = e.getY();
                updateCursor();
            } else if (e.getButton() == MouseButton.PRIMARY && shownLoaded != null) {
                loupeX = e.getX();
                loupeY = e.getY();
                loupeDelay.setOnFinished(f -> startLoupe());   // held: the loupe, not a click
                loupeDelay.playFromStart();
            }
        });
        setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY || !e.isStillSincePress()) {
                return;   // a drag (pan, loupe) is not a click
            }
            if (e.getClickCount() == 2) {
                clickDelay.stop();   // the first click's navigation never happens
                doubleClick.run();
            } else if (e.getClickCount() == 1 && !loupeShown) {
                Runnable go = e.getX() < getWidth() / 2 ? previous : next;
                clickDelay.setOnFinished(f -> go.run());
                clickDelay.playFromStart();
            }
        });
        setOnMouseMoved(e -> pointerMoved());
        setOnMouseDragged(e -> {
            pointerMoved();
            if (loupeDelay.getStatus() == Animation.Status.RUNNING) {
                loupeDelay.stop();   // moving while held: the loupe at once
                loupeX = e.getX();
                loupeY = e.getY();
                startLoupe();
            }
            if (magnifying) {
                showLoupe(e.getX(), e.getY());
            }
            if (dragging) {
                controller.pan((e.getX() - dragX) * scale, (e.getY() - dragY) * scale);
                dragX = e.getX();
                dragY = e.getY();
            }
        });
        setOnMouseReleased(e -> {
            if (e.getButton() == MouseButton.PRIMARY && dragging) {
                dragging = false;
                updateCursor();
            }
            if (e.getButton() == MouseButton.PRIMARY) {
                loupeDelay.stop();
                if (magnifying) {
                    hideLoupe();
                }
            }
        });
        idle.setOnFinished(e -> {
            pointerHidden = true;
            updateCursor();
        });
    }

    /**
     * The colour around the image and behind its transparent parts (R-FS-06), {@code #rrggbb}; messages are dark on a
     * light colour.
     */
    public void setBackgroundColour(String colour) {
        setStyle("-fx-background-color: " + colour + ";");
        boolean light = Color.web(colour).getBrightness() > 0.6;
        message.setStyle("-fx-text-fill: " + (light ? "#303030" : "#d0d0d0") + "; -fx-font-size: 16px;");
    }

    /**
     * Where the size, the wheel and drags go, and the window whose render scale converts them and whose full-screen
     * hides the idle pointer; set once, before any image is shown.
     */
    public void connect(ViewerController<Image> controller, Stage window) {
        this.controller = controller;
        InvalidationListener resized = obs -> {
            scale = window.getRenderScaleX();
            controller.resize(getWidth() * scale, getHeight() * scale);
        };
        widthProperty().addListener(resized);
        heightProperty().addListener(resized);
        window.renderScaleXProperty().addListener(resized);
        window.fullScreenProperty().addListener((obs, was, now) -> {
            fullScreen = now;
            pointerMoved();   // entering: hides after a while; leaving: shown, and stays
        });
    }

    /** While a full-screen panel is open the pointer doesn't hide (#27). */
    void keepPointer(boolean keep) {
        pointerKept = keep;
        pointerMoved();
    }

    /** The pointer shows again; in full-screen it hides once the mouse rests for {@link #POINTER_IDLE}. */
    private void pointerMoved() {
        pointerHidden = false;
        updateCursor();
        if (fullScreen && !pointerKept) {
            idle.playFromStart();
        } else {
            idle.stop();
        }
    }

    /** Turns the wheel's pixels into notches; Ctrl + wheel zooms at the mouse, the plain wheel follows the setting. */
    private void scrolled(ScrollEvent e) {
        if (controller == null) {
            return;
        }
        e.consume();
        double notchesX = e.getDeltaX() / notch(e.getMultiplierX());
        double notchesY = e.getDeltaY() / notch(e.getMultiplierY());
        if (e.isControlDown()) {
            controller.zoomWheel(notchesY, e.getX() * scale, e.getY() * scale);
        } else {
            controller.wheel(notchesX, notchesY);
        }
    }

    private static double notch(double multiplier) {
        return multiplier > 0 ? multiplier : 40;   // 40 = JavaFX's usual wheel notch
    }

    /**
     * {@code loaded} turned upright with its orientation (R-FMT-03), at the scale and place in {@code v}, in physical
     * pixels. The scale is for the real size: a smaller screen-size copy (#19) is stretched to it. The corner goes on a
     * whole physical pixel, else at actual size every pixel would be blended with its neighbour.
     */
    public void show(Loaded<Image> loaded, Zoom.View v) {
        Image image = loaded.image();
        Orientation orientation = loaded.orientation();
        message.setText("");
        // ImageView scales before it rotates, both around its centre: mirror first, then rotate, as EXIF defines.
        view.setScaleX(orientation.mirror() ? -1 : 1);
        view.setRotate(orientation.degrees());
        if (sharp != null && image == sharpSource && v.scale() == sharpScale) {
            showSharp();   // only moved, e.g. a drag
        } else {
            view.setImage(image);
            // The fit sizes apply before turning, so they use the stored width and height.
            view.setFitWidth(loaded.width() * v.scale() / scale);
            view.setFitHeight(loaded.height() * v.scale() / scale);
            redrawSharp(loaded, v.scale());
        }
        turned.relocate(Math.round(v.x()) / scale, Math.round(v.y()) / scale);

        double width = loaded.uprightWidth() * v.scale() / scale;
        double height = loaded.uprightHeight() * v.scale() / scale;
        // Half a pixel of slack: a fit image is not pannable because of rounding.
        setPannable(width > getWidth() + 0.5 || height > getHeight() + 0.5);
        shownLoaded = loaded;
        shownView = v;
        if (magnifying) {
            showLoupe(loupeX, loupeY);   // e.g. the full image arrived
        }
    }

    /** What a click does: previous image, next image, and a double-click (#62). */
    void onClicks(Runnable previous, Runnable next, Runnable doubleClick) {
        this.previous = previous;
        this.next = next;
        this.doubleClick = doubleClick;
    }

    private void startLoupe() {
        if (shownLoaded == null) {
            return;
        }
        magnifying = true;
        loupeShown = true;   // its release is not a click
        controller.loadFullResolution();
        showLoupe(loupeX, loupeY);
    }

    private void hideLoupe() {
        magnifying = false;
        lens.setVisible(false);
        lensRing.setVisible(false);
        lensView.setImage(null);
    }

    /** The loupe centred on ({@code x}, {@code y}), showing the image point under it. */
    private void showLoupe(double x, double y) {
        loupeX = x;
        loupeY = y;
        Loaded<Image> loaded = shownLoaded;
        Zoom.View v = shownView;
        if (loaded == null || v == null) {
            return;
        }
        Orientation orientation = loaded.orientation();
        lensView.setImage(loaded.image());   // the decoded image, never the shrunk sharp copy
        lensView.setScaleX(orientation.mirror() ? -1 : 1);
        lensView.setRotate(orientation.degrees());
        double perPixel = LOUPE_ZOOM / scale;   // JavaFX pixels per image pixel
        lensView.setFitWidth(loaded.width() * perPixel);
        lensView.setFitHeight(loaded.height() * perPixel);
        // The image point under the mouse, in upright image pixels.
        double imageX = (x * scale - Math.round(v.x())) / v.scale();
        double imageY = (y * scale - Math.round(v.y())) / v.scale();
        lensTurned.relocate(x - imageX * perPixel, y - imageY * perPixel);
        lensClip.setCenterX(x);
        lensClip.setCenterY(y);
        lensRing.setCenterX(x);
        lensRing.setCenterY(y);
        lens.setVisible(true);
        lensRing.setVisible(true);
    }

    public void showMessage(String text) {
        stopRedraw();
        shownLoaded = null;
        shownView = null;
        hideLoupe();
        view.setImage(null);
        message.setText(text);
        setPannable(false);
    }

    private void showSharp() {
        view.setImage(sharp);
        view.setFitWidth(sharp.getWidth() / scale);
        view.setFitHeight(sharp.getHeight() / scale);
    }

    private void stopRedraw() {
        redraw++;
        settle.stop();
        sharp = null;
        sharpSource = null;
    }

    /** Starts the sharp redraw of {@code loaded} at {@code zoom} once the view has settled, if it shrinks. */
    private void redrawSharp(Loaded<Image> loaded, double zoom) {
        stopRedraw();
        Image image = loaded.image();
        int toWidth = (int) Math.round(loaded.width() * zoom);
        int toHeight = (int) Math.round(loaded.height() * zoom);
        if (toWidth < 1 || toHeight < 1 || toWidth >= image.getWidth()
                || image.getWidth() * image.getHeight() > ImageResampler.MAX_SOURCE_PIXELS || isGif(image)) {
            return;
        }
        long id = redraw;
        settle.setOnFinished(e -> resampler.execute(() -> resample(id, image, zoom, toWidth, toHeight)));
        settle.playFromStart();
    }

    /** On the resampler thread; the result is shown only if nothing changed meanwhile. */
    private void resample(long id, Image image, double zoom, int toWidth, int toHeight) {
        if (redraw != id) {
            return;
        }
        try {
            int width = (int) image.getWidth();
            int height = (int) image.getHeight();
            int[] pixels = new int[width * height];
            image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbPreInstance(), pixels, 0,
                    width);
            int[] shrunk = ImageResampler.lanczos(pixels, width, height, toWidth, toHeight, () -> redraw != id);
            if (shrunk == null) {
                return;
            }
            WritableImage result = new WritableImage(toWidth, toHeight);
            result.getPixelWriter().setPixels(0, 0, toWidth, toHeight, PixelFormat.getIntArgbPreInstance(), shrunk, 0,
                    toWidth);
            Platform.runLater(() -> {
                if (redraw == id) {
                    sharp = result;
                    sharpSource = image;
                    sharpScale = zoom;
                    showSharp();
                }
            });
        } catch (OutOfMemoryError | RuntimeException e) {
            // The graphics card's version stays: less sharp, still correct.
            System.getLogger("linview").log(System.Logger.Level.WARNING, "Sharp redraw skipped: " + e);
        }
    }

    /** JavaFX loads GIFs from their file; an animated one would stop on its first frame. */
    private static boolean isGif(Image image) {
        String url = image.getUrl();
        return url != null && url.toLowerCase(Locale.ROOT).endsWith(".gif");
    }

    private void setPannable(boolean value) {
        pannable = value;
        updateCursor();
    }

    /** Hidden while idle in full-screen; else the closed hand while dragging, the grab hand when it can be dragged. */
    private void updateCursor() {
        setCursor(pointerHidden ? Cursor.NONE
                : dragging ? Cursor.CLOSED_HAND : pannable ? Cursor.OPEN_HAND : Cursor.DEFAULT);
    }
}
