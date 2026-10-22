package linview;

import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

/**
 * Shows one image, or a message instead of it. Size and position come from {@link Zoom}: a new image follows the
 * zoom mode in {@link Settings}; + / − zoom only the image on screen (R-ZOOM-01).
 */
final class ViewerPane extends StackPane {

    private final Settings settings;
    private final ImageView view = new ImageView();
    /** Holds the turned image, so its layout bounds are the upright size and it can be placed by its corner. */
    private final Group turned = new Group(view);
    private final Label message = new Label();
    private final ReadOnlyDoubleWrapper scale = new ReadOnlyDoubleWrapper(0);
    private Orientation orientation = Orientation.UPRIGHT;
    private Zoom.View shown;
    /** True after + / −: a resize keeps that zoom instead of applying the mode again. */
    private boolean zoomedByHand;

    ViewerPane(Settings settings) {
        this.settings = settings;
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
        widthProperty().addListener((obs, old, now) -> resized());
        heightProperty().addListener((obs, old, now) -> resized());
    }

    /** {@code image} must be fully loaded, so its size is known; {@code orientation} turns it upright (R-FMT-03). */
    void show(Image image, Orientation orientation) {
        this.orientation = orientation;
        message.setText("");
        // ImageView scales before it rotates, both around its centre: mirror first, then rotate, as EXIF defines.
        view.setScaleX(orientation.mirror() ? -1 : 1);
        view.setRotate(orientation.degrees());
        view.setImage(image);
        applyMode();
    }

    void showMessage(String text) {
        view.setImage(null);
        shown = null;
        scale.set(0);
        message.setText(text);
    }

    Image image() {
        return view.getImage();
    }

    /** Current zoom, 1 = actual size; 0 while no image is shown. */
    ReadOnlyDoubleProperty scaleProperty() {
        return scale.getReadOnlyProperty();
    }

    /** Size of the image as shown upright, at actual size. */
    double uprightWidth() {
        Image image = view.getImage();
        return orientation.swapsSides() ? image.getHeight() : image.getWidth();
    }

    double uprightHeight() {
        Image image = view.getImage();
        return orientation.swapsSides() ? image.getWidth() : image.getHeight();
    }

    /** Sizes and places the image from the zoom mode in the settings, dropping any + / − zoom. */
    void applyMode() {
        zoomedByHand = false;
        if (!canLayOut()) {
            return;
        }
        double s = Zoom.scale(settings.zoomMode, settings.zoomPercent, settings.enlargeSmall,
                uprightWidth(), uprightHeight(), getWidth(), getHeight());
        place(Zoom.start(s, uprightWidth(), uprightHeight(), getWidth(), getHeight()));
    }

    void zoomIn() {
        zoomTo(shown == null ? 0 : Zoom.zoomIn(shown.scale()));
    }

    void zoomOut() {
        zoomTo(shown == null ? 0 : Zoom.zoomOut(shown.scale()));
    }

    /** Keeps the point in the middle of the window where it is. */
    private void zoomTo(double newScale) {
        if (shown == null || !canLayOut()) {
            return;
        }
        zoomedByHand = true;
        place(Zoom.around(shown, newScale, getWidth() / 2, getHeight() / 2,
                uprightWidth(), uprightHeight(), getWidth(), getHeight()));
    }

    /** Fit modes follow the new window size; a + / − zoom is kept and only tidied. */
    private void resized() {
        if (!zoomedByHand || shown == null) {
            applyMode();
        } else if (canLayOut()) {
            place(Zoom.around(shown, shown.scale(), 0, 0, uprightWidth(), uprightHeight(), getWidth(), getHeight()));
        }
    }

    private boolean canLayOut() {
        return view.getImage() != null && getWidth() > 0 && getHeight() > 0;
    }

    private void place(Zoom.View v) {
        shown = v;
        Image image = view.getImage();
        // The fit sizes apply before turning, so they use the stored width and height.
        view.setFitWidth(image.getWidth() * v.scale());
        view.setFitHeight(image.getHeight() * v.scale());
        turned.relocate(v.x(), v.y());
        scale.set(v.scale());
    }
}
