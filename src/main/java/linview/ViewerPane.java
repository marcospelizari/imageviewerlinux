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

    /** {@code image} must be fully loaded, so its size is known. */
    void show(Image image) {
        message.setText("");
        view.setImage(image);
        fit();
    }

    void showMessage(String text) {
        view.setImage(null);
        message.setText(text);
    }

    private void fit() {
        Image image = view.getImage();
        if (image == null) {
            return;
        }
        view.setFitWidth(Math.min(image.getWidth(), getWidth()));
        view.setFitHeight(Math.min(image.getHeight(), getHeight()));
    }
}
