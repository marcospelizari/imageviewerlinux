package linview.ui;

import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import linview.image.Orientation;
import linview.viewer.ViewerController;
import linview.viewer.Zoom;

/**
 * Shows one image, or a message instead of it. Where the image goes and how large it is comes from the
 * {@code ViewerController}; this pane only draws it, and passes Ctrl + wheel and drags on to it (R-ZOOM-05).
 */
public final class ViewerPane extends StackPane {

    private final ImageView view = new ImageView();
    /** Holds the turned image, so its layout bounds are the upright size and it can be placed by its corner. */
    private final Group turned = new Group(view);
    private final Label message = new Label();
    private ViewerController<Image> controller;
    /** The image is larger than the pane on some side, so a drag moves it. */
    private boolean pannable;
    private boolean dragging;
    private double dragX;
    private double dragY;
    /** Wheel movement not yet a whole zoom step: a touchpad sends many small ones. */
    private double wheel;

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
                controller.pan(e.getX() - dragX, e.getY() - dragY);
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

    /** Where Ctrl + wheel and drags go; set once, before any image is shown. */
    public void sendGesturesTo(ViewerController<Image> controller) {
        this.controller = controller;
    }

    /** Ctrl + wheel: one zoom step per notch at the mouse. The plain wheel comes with #15. */
    private void scrolled(ScrollEvent e) {
        if (!e.isControlDown() || controller == null) {
            return;
        }
        e.consume();
        double notch = e.getMultiplierY() > 0 ? e.getMultiplierY() : 40;   // 40 = JavaFX's usual wheel notch
        if (wheel * e.getDeltaY() < 0) {
            wheel = 0;   // direction changed: don't let leftovers zoom the wrong way
        }
        wheel += e.getDeltaY();
        while (Math.abs(wheel) >= notch) {
            if (wheel > 0) {
                controller.zoomInAt(e.getX(), e.getY());
            } else {
                controller.zoomOutAt(e.getX(), e.getY());
            }
            wheel -= Math.signum(wheel) * notch;
        }
    }

    /** {@code image} turned upright with {@code orientation} (R-FMT-03), at the scale and place in {@code v}. */
    public void show(Image image, Orientation orientation, Zoom.View v) {
        message.setText("");
        // ImageView scales before it rotates, both around its centre: mirror first, then rotate, as EXIF defines.
        view.setScaleX(orientation.mirror() ? -1 : 1);
        view.setRotate(orientation.degrees());
        view.setImage(image);
        // The fit sizes apply before turning, so they use the stored width and height.
        view.setFitWidth(image.getWidth() * v.scale());
        view.setFitHeight(image.getHeight() * v.scale());
        turned.relocate(v.x(), v.y());

        boolean sideways = orientation.swapsSides();
        double width = (sideways ? image.getHeight() : image.getWidth()) * v.scale();
        double height = (sideways ? image.getWidth() : image.getHeight()) * v.scale();
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
