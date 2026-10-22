package linview.ui;

import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

import linview.image.Orientation;
import linview.viewer.Zoom;

/**
 * Shows one image, or a message instead of it. Where the image goes and how large it is comes from the
 * {@code ViewerController}; this pane only draws it.
 */
public final class ViewerPane extends StackPane {

    private final ImageView view = new ImageView();
    /** Holds the turned image, so its layout bounds are the upright size and it can be placed by its corner. */
    private final Group turned = new Group(view);
    private final Label message = new Label();

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
    }

    public void showMessage(String text) {
        view.setImage(null);
        message.setText(text);
    }
}
