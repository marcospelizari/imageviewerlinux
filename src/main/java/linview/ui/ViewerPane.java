package linview.ui;

import javafx.beans.InvalidationListener;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.stage.Window;

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
 */
public final class ViewerPane extends StackPane {

    private final ImageView view = new ImageView();
    /** Holds the turned image, so its layout bounds are the upright size and it can be placed by its corner. */
    private final Group turned = new Group(view);
    private final Label message = new Label();
    private ViewerController<Image> controller;
    /** Physical pixels per JavaFX pixel. */
    private double scale = 1;
    /** The image is larger than the pane on some side, so a drag moves it. */
    private boolean pannable;
    private boolean dragging;
    private double dragX;
    private double dragY;

    public ViewerPane() {
        setStyle("-fx-background-color: black;");
        view.setSmooth(true);
        message.setStyle("-fx-text-fill: #d0d0d0; -fx-font-size: 16px;");
        message.setWrapText(true);
        turned.setManaged(false);   // placed by hand; the pane must not centre it or grow to the image's size
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
        getChildren().addAll(turned, message);

        setOnScroll(this::scrolled);
        setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY && pannable) {
                dragging = true;
                dragX = e.getX();
                dragY = e.getY();
                setCursor(Cursor.CLOSED_HAND);
            }
        });
        setOnMouseDragged(e -> {
            if (dragging) {
                controller.pan((e.getX() - dragX) * scale, (e.getY() - dragY) * scale);
                dragX = e.getX();
                dragY = e.getY();
            }
        });
        setOnMouseReleased(e -> {
            if (e.getButton() == MouseButton.PRIMARY && dragging) {
                dragging = false;
                setCursor(pannable ? Cursor.OPEN_HAND : Cursor.DEFAULT);
            }
        });
    }

    /**
     * Where the size, the wheel and drags go, and the window whose render scale converts them; set once, before any
     * image is shown.
     */
    public void connect(ViewerController<Image> controller, Window window) {
        this.controller = controller;
        InvalidationListener resized = obs -> {
            scale = window.getRenderScaleX();
            controller.resize(getWidth() * scale, getHeight() * scale);
        };
        widthProperty().addListener(resized);
        heightProperty().addListener(resized);
        window.renderScaleXProperty().addListener(resized);
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
        view.setImage(image);
        // The fit sizes apply before turning, so they use the stored width and height.
        view.setFitWidth(loaded.width() * v.scale() / scale);
        view.setFitHeight(loaded.height() * v.scale() / scale);
        turned.relocate(Math.round(v.x()) / scale, Math.round(v.y()) / scale);

        double width = loaded.uprightWidth() * v.scale() / scale;
        double height = loaded.uprightHeight() * v.scale() / scale;
        // Half a pixel of slack: a fit image is not pannable because of rounding.
        setPannable(width > getWidth() + 0.5 || height > getHeight() + 0.5);
    }

    public void showMessage(String text) {
        view.setImage(null);
        message.setText(text);
        setPannable(false);
    }

    /** Grab hand while there is something to drag; the closed hand stays until the drag ends. */
    private void setPannable(boolean value) {
        pannable = value;
        if (!dragging) {
            setCursor(pannable ? Cursor.OPEN_HAND : Cursor.DEFAULT);
        }
    }
}
