package linview.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.function.IntConsumer;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.css.PseudoClass;
import javafx.geometry.Orientation;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;

import linview.image.thumbnail.ThumbnailLoader;

/**
 * The full-screen top panel (R-FS-03, #28): the open folder's thumbnails in a row, the selected image highlighted
 * and scrolled into view when the strip opens. The wheel scrolls it; a click opens that image. Only the cells in view
 * exist and ask for thumbnails, so a 500-image folder opens at once and its thumbnails fill in. It never takes the
 * keyboard focus, so ← → keep moving through the images.
 */
final class ThumbStrip extends ListView<Path> {

    /** Thumbnail box in JavaFX pixels, so it follows the desktop scaling (Marcos, 2026-10-24). */
    static final double THUMB_HEIGHT = 120;
    static final double THUMB_WIDTH = 160;
    private static final double CELL_PADDING = 4;

    private static final PseudoClass CURRENT = PseudoClass.getPseudoClass("current");

    private final ThumbnailLoader thumbnails;
    private final IntegerProperty current = new SimpleIntegerProperty(-1);
    private List<Path> files = List.of();

    /** {@code select} gets the index of a clicked thumbnail. */
    ThumbStrip(ThumbnailLoader thumbnails, IntConsumer select) {
        this.thumbnails = thumbnails;
        getStyleClass().add("thumb-strip");
        setOrientation(Orientation.HORIZONTAL);
        setFocusTraversable(false);
        setFixedCellSize(THUMB_WIDTH + 2 * CELL_PADDING);
        setPrefHeight(THUMB_HEIGHT + 2 * CELL_PADDING + 12);
        setCellFactory(list -> new Thumb(select));
        // The list's own selection and focus would take ← → from the viewer: clicks are handled by the cells.
        addEventFilter(MouseEvent.MOUSE_PRESSED, MouseEvent::consume);
        addEventHandler(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);   // a click here never reaches the image
        addEventFilter(ScrollEvent.SCROLL, e -> {
            VirtualFlow<?> flow = (VirtualFlow<?>) lookup(".virtual-flow");
            if (flow != null) {
                flow.scrollPixels(-(e.getDeltaY() != 0 ? e.getDeltaY() : e.getDeltaX()));
            }
            e.consume();
        });
    }

    /** The folder and the selected image (0-based), from the controller; a new folder replaces the thumbnails. */
    void show(List<Path> folder, int index) {
        if (folder != files) {
            files = folder;
            thumbnails.forgetWaiting();
            setItems(FXCollections.observableList(folder));
        }
        current.set(index);
        if (isVisible()) {
            scrollToCurrent();
        }
    }

    /** The selected image in the middle, as far as the ends allow. */
    void scrollToCurrent() {
        if (current.get() >= 0) {
            int visible = (int) (getWidth() / getFixedCellSize());
            scrollTo(Math.max(0, current.get() - visible / 2));
        }
    }

    private final class Thumb extends ListCell<Path> {

        private final ImageView view = new ImageView();

        Thumb(IntConsumer select) {
            view.setFitWidth(THUMB_WIDTH);
            view.setFitHeight(THUMB_HEIGHT);
            view.setPreserveRatio(true);
            view.setSmooth(true);
            setPrefHeight(THUMB_HEIGHT + 2 * CELL_PADDING);
            current.addListener(obs -> highlight());
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && getItem() != null && e.getClickCount() == 1) {
                    select.accept(getIndex());
                }
            });
        }

        @Override
        protected void updateItem(Path file, boolean empty) {
            super.updateItem(file, empty);
            view.setImage(null);
            highlight();
            if (empty || file == null) {
                setGraphic(null);
                return;
            }
            setGraphic(view);
            thumbnails.request(file, (Image image) -> {
                if (file.equals(getItem())) {   // the cell may show another file by now
                    view.setImage(image);
                }
            });
        }

        private void highlight() {
            pseudoClassStateChanged(CURRENT, !isEmpty() && getIndex() == current.get());
        }
    }
}
