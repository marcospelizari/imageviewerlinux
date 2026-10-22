package linview;

import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

/**
 * Shows one image centred, or a message instead of it.
 * Fit for now: the whole image visible, small images not enlarged. The zoom modes (#10, #11) replace this.
 */
final class ViewerPane extends StackPane {

    private final ImageView view = new ImageView();
    private final Label message = new Label();
    private Orientation orientation = Orientation.UPRIGHT;

    ViewerPane() {
        setStyle("-fx-background-color: black;");
        view.setPreserveRatio(true);
        view.setSmooth(true);
        message.setStyle("-fx-text-fill: #d0d0d0; -fx-font-size: 16px;");
        message.setWrapText(true);
        getChildren().addAll(view, message);
        widthProperty().addListener((obs, old, now) -> fit());
        heightProperty().addListener((obs, old, now) -> fit());
    }

    /** {@code image} must be fully loaded, so its size is known; {@code orientation} turns it upright (R-FMT-03). */
    void show(Image image, Orientation orientation) {
        this.orientation = orientation;
        message.setText("");
        // ImageView scales before it rotates, both around its centre: mirror first, then rotate, as EXIF defines.
        view.setScaleX(orientation.mirror() ? -1 : 1);
        view.setRotate(orientation.degrees());
        view.setImage(image);
        fit();
    }

    void showMessage(String text) {
        view.setImage(null);
        message.setText(text);
    }

    private void fit() {
        Image image = view.getImage();
        if (image == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        // The fit sizes apply before rotating, so compare the turned image with the window.
        double width = image.getWidth();
        double height = image.getHeight();
        double shownWidth = orientation.swapsSides() ? height : width;
        double shownHeight = orientation.swapsSides() ? width : height;
        double scale = Math.min(1, Math.min(getWidth() / shownWidth, getHeight() / shownHeight));
        view.setFitWidth(width * scale);
        view.setFitHeight(height * scale);
    }
}
