package linview.ui;

import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * The full-screen edge panels (#27, REPORT.md §3.3): a panel floats over the image, so the image never moves; it
 * opens after the mouse has rested {@link #OPEN_DELAY} at its edge and slides back {@link #CLOSE_DELAY} after the
 * mouse has left it. Hover only, no pinning; in a window the edges stay plain (Marcos, 2026-10-24). While a panel is
 * open the mouse pointer stays visible. Version 1 has the top panel (thumbnails, #28); the bottom-left taskbar (#29)
 * comes next.
 */
final class EdgePanels {

    static final Duration OPEN_DELAY = Duration.millis(200);
    static final Duration CLOSE_DELAY = Duration.millis(300);
    private static final Duration SLIDE = Duration.millis(150);
    /** How close to the edge the mouse must be, in JavaFX pixels. */
    private static final double EDGE = 2;

    /** Lies over the image; lets the mouse through except over a panel. */
    private final Pane layer = new Pane();
    private final Stage stage;
    private final ViewerPane viewer;
    private Region top;
    private Runnable onTopOpened = () -> { };
    private boolean open;
    private final PauseTransition opening = new PauseTransition(OPEN_DELAY);
    private final PauseTransition closing = new PauseTransition(CLOSE_DELAY);
    private final TranslateTransition slide = new TranslateTransition(SLIDE);

    EdgePanels(Stage stage, ViewerPane viewer) {
        this.stage = stage;
        this.viewer = viewer;
        layer.setPickOnBounds(false);
        opening.setOnFinished(e -> openTop());
        closing.setOnFinished(e -> closeTop());
        stage.fullScreenProperty().addListener((obs, was, now) -> {
            if (!now) {
                opening.stop();
                closing.stop();
                hideTop();
            }
        });
    }

    Pane layer() {
        return layer;
    }

    /** {@code panel} along the top edge, full width; {@code onOpened} runs each time it opens. */
    void setTop(Region panel, Runnable onOpened) {
        top = panel;
        onTopOpened = onOpened;
        panel.setManaged(false);
        panel.setVisible(false);
        layer.getChildren().add(panel);
        layer.widthProperty().addListener(obs -> layOut());
        slide.setNode(panel);
    }

    /** Follows the mouse over the whole window, image and panels alike. */
    void watch(Scene scene) {
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> mouseAt(e.getSceneY()));
    }

    private void layOut() {
        top.resize(layer.getWidth(), top.prefHeight(layer.getWidth()));
        top.relocate(0, 0);
    }

    private void mouseAt(double y) {
        if (top == null || !stage.isFullScreen()) {
            return;
        }
        if (open) {
            if (y <= top.getHeight()) {
                closing.stop();   // still over the panel: no flicker while the mouse rests there
            } else if (closing.getStatus() != Animation.Status.RUNNING) {
                closing.playFromStart();
            }
        } else if (y <= EDGE) {
            if (opening.getStatus() != Animation.Status.RUNNING) {
                opening.playFromStart();
            }
        } else {
            opening.stop();   // only passing the edge
        }
    }

    private void openTop() {
        if (open || !stage.isFullScreen()) {
            return;
        }
        open = true;
        layOut();
        top.setVisible(true);
        viewer.keepPointer(true);
        onTopOpened.run();
        slide.stop();
        slide.setOnFinished(null);
        slide.setFromY(-top.getHeight());
        slide.setToY(0);
        slide.playFromStart();
    }

    private void closeTop() {
        if (!open) {
            return;
        }
        open = false;
        viewer.keepPointer(false);
        slide.stop();
        slide.setFromY(top.getTranslateY());
        slide.setToY(-top.getHeight());
        slide.setOnFinished(e -> top.setVisible(false));
        slide.playFromStart();
    }

    private void hideTop() {
        if (top == null) {
            return;
        }
        open = false;
        slide.stop();
        top.setVisible(false);
        viewer.keepPointer(false);
    }
}
