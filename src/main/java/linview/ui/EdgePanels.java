package linview.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * The full-screen edge panels (#27, REPORT.md §3.3): a panel floats over the image, so the image never moves; it
 * opens after the mouse has rested {@link #OPEN_DELAY} at its edge and slides back {@link #CLOSE_DELAY} after the
 * mouse has left it. Hover only, no pinning; in a window the edges stay plain (Marcos, 2026-10-24). While a panel is
 * open the mouse pointer stays visible. Version 1 has the top panel (thumbnails, #28) and the bottom-left taskbar
 * (#29).
 */
final class EdgePanels {

    static final Duration OPEN_DELAY = Duration.millis(200);
    static final Duration CLOSE_DELAY = Duration.millis(300);
    private static final Duration SLIDE = Duration.millis(150);
    /** How close to the edge the mouse must be, in JavaFX pixels. */
    private static final double EDGE = 2;

    /**
     * Lies over the image; lets the mouse through except over a panel. It lays the panels out itself, so a panel
     * whose contents grow (a longer image size) grows with them.
     */
    private final StackPane layer = new StackPane();
    private final Stage stage;
    private final ViewerPane viewer;
    private final List<Panel> panels = new ArrayList<>();

    EdgePanels(Stage stage, ViewerPane viewer) {
        this.stage = stage;
        this.viewer = viewer;
        layer.setPickOnBounds(false);
        stage.fullScreenProperty().addListener((obs, was, now) -> {
            if (!now) {
                panels.forEach(Panel::hide);
            }
        });
    }

    StackPane layer() {
        return layer;
    }

    /** {@code panel} along the top edge, full width; {@code onOpened} runs each time it opens. */
    void addTop(Region panel, Runnable onOpened) {
        add(new Panel(panel, true, onOpened, () -> false, open -> { }));
    }

    /**
     * {@code panel} in the bottom-left corner, as wide as its contents. It opens when the mouse reaches the bottom
     * edge below it: the laptop screen's left edge borders the other monitor, so a corner alone can't always be hit.
     * It stays open while {@code keepOpen} says so (typing in it); {@code openChanged} hears each change.
     */
    void addBottomLeft(Region panel, BooleanSupplier keepOpen, Consumer<Boolean> openChanged) {
        add(new Panel(panel, false, () -> { }, keepOpen, openChanged));
    }

    private void add(Panel panel) {
        panels.add(panel);
        layer.getChildren().add(panel.node);
        if (panel.top) {
            panel.node.setMaxHeight(Region.USE_PREF_SIZE);   // full width, its own height
            StackPane.setAlignment(panel.node, Pos.TOP_CENTER);
        } else {
            panel.node.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);   // only as large as its contents
            StackPane.setAlignment(panel.node, Pos.BOTTOM_LEFT);
        }
    }

    /**
     * Follows the mouse over the whole window, image and panels alike. Leaving the window, e.g. straight onto the
     * other monitor, is like leaving the panels (#67).
     */
    void watch(Scene scene) {
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            if (stage.isFullScreen()) {
                panels.forEach(panel -> panel.mouseAt(e.getSceneX(), e.getSceneY()));
            }
        });
        scene.addEventHandler(MouseEvent.MOUSE_EXITED, e -> panels.forEach(Panel::mouseLeft));
    }

    private void pointerKept() {
        viewer.keepPointer(panels.stream().anyMatch(panel -> panel.open));
    }

    private final class Panel {

        final Region node;
        final boolean top;
        final Runnable onOpened;
        final BooleanSupplier keepOpen;
        final Consumer<Boolean> openChanged;
        boolean open;
        final PauseTransition opening = new PauseTransition(OPEN_DELAY);
        final PauseTransition closing = new PauseTransition(CLOSE_DELAY);
        final TranslateTransition slide = new TranslateTransition(SLIDE);

        Panel(Region node, boolean top, Runnable onOpened, BooleanSupplier keepOpen, Consumer<Boolean> openChanged) {
            this.node = node;
            this.top = top;
            this.onOpened = onOpened;
            this.keepOpen = keepOpen;
            this.openChanged = openChanged;
            node.setVisible(false);
            slide.setNode(node);
            opening.setOnFinished(e -> open());
            closing.setOnFinished(e -> {
                if (keepOpen.getAsBoolean()) {
                    closing.playFromStart();   // still typing: look again later
                } else {
                    close();
                }
            });
        }

        /** The mouse at its edge, below the panel for the bottom-left one. */
        boolean atEdge(double x, double y) {
            return top ? y <= EDGE : y >= layer.getHeight() - EDGE && x <= node.getWidth();
        }

        boolean over(double x, double y) {
            return top ? y <= node.getHeight()
                    : x <= node.getWidth() && y >= layer.getHeight() - node.getHeight();
        }

        void mouseAt(double x, double y) {
            if (open) {
                if (over(x, y)) {
                    closing.stop();   // still over the panel: no flicker while the mouse rests there
                } else if (closing.getStatus() != Animation.Status.RUNNING) {
                    closing.playFromStart();
                }
            } else if (atEdge(x, y)) {
                if (opening.getStatus() != Animation.Status.RUNNING) {
                    opening.playFromStart();
                }
            } else {
                opening.stop();   // only passing the edge
            }
        }

        /** Not opening; an open one closes as when the mouse moves off it, so typing still keeps it. */
        void mouseLeft() {
            opening.stop();
            if (open && closing.getStatus() != Animation.Status.RUNNING) {
                closing.playFromStart();
            }
        }

        void open() {
            if (open || !stage.isFullScreen()) {
                return;
            }
            open = true;
            node.setVisible(true);
            pointerKept();
            onOpened.run();
            openChanged.accept(true);
            slide.stop();
            slide.setOnFinished(null);
            slide.setFromY(top ? -node.getHeight() : node.getHeight());
            slide.setToY(0);
            slide.playFromStart();
        }

        void close() {
            if (!open) {
                return;
            }
            open = false;
            pointerKept();
            openChanged.accept(false);
            slide.stop();
            slide.setFromY(node.getTranslateY());
            slide.setToY(top ? -node.getHeight() : node.getHeight());
            slide.setOnFinished(e -> node.setVisible(false));
            slide.playFromStart();
        }

        void hide() {
            opening.stop();
            closing.stop();
            slide.stop();
            node.setVisible(false);
            if (open) {
                open = false;
                pointerKept();
                openChanged.accept(false);
            }
        }
    }
}
