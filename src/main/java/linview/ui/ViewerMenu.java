package linview.ui;

import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Window;

import linview.viewer.Zoom;

/**
 * The right-click menu (R-VIEW-05, #49): the main actions with their keys, so they can be found without knowing the
 * keys. Each item carries out the same action as its key, through {@link ViewerWindow#execute}. Built anew for each
 * right-click from the state at that moment. The keys are shown as text, not as JavaFX accelerators, which would
 * register them a second time beside {@link Keys}.
 */
final class ViewerMenu {

    /** What the menu needs to know when it opens. */
    record State(boolean imageShown, boolean canTrash, Zoom.Mode mode, boolean fullScreen) {
    }

    private final Consumer<Keys.Action> act;
    private final ContextMenu menu = new ContextMenu();
    /** A press outside the open menu closed it: its release and click are swallowed too, so it doesn't navigate. */
    private boolean swallowing;

    /** {@code open} hears when the menu opens and closes, so the full-screen pointer doesn't hide meanwhile. */
    ViewerMenu(Consumer<Keys.Action> act, Consumer<Boolean> open) {
        this.act = act;
        menu.setOnShown(e -> open.accept(true));
        menu.setOnHidden(e -> open.accept(false));
    }

    /** Opens the menu at ({@code screenX}, {@code screenY}) over {@code anchor}; an open one closes first. */
    void show(Node anchor, double screenX, double screenY, State state) {
        menu.hide();
        boolean locked = state.mode() == Zoom.Mode.LOCK;
        menu.getItems().setAll(
                item("Open…", "Ctrl+O", false, true, Keys.Action.OPEN),
                new SeparatorMenuItem(),
                item("Fit to window", "B", state.mode() == Zoom.Mode.FIT, true, Keys.Action.FIT),
                item("Fit width", "W", state.mode() == Zoom.Mode.WIDTH, true, Keys.Action.FIT_WIDTH),
                item("Fit height", "", state.mode() == Zoom.Mode.HEIGHT, true, Keys.Action.FIT_HEIGHT),
                item("Actual size (100%)", "1", state.mode() == Zoom.Mode.ACTUAL, true, Keys.Action.ACTUAL_SIZE),
                // Locking takes the % of the image on screen; unlocking is always possible.
                item("Zoom lock", "Ctrl+Shift+L", locked, state.imageShown() || locked, Keys.Action.ZOOM_LOCK),
                new SeparatorMenuItem(),
                item("Rotate right", "R", false, state.imageShown(), Keys.Action.ROTATE_RIGHT),
                item("Rotate left", "L", false, state.imageShown(), Keys.Action.ROTATE_LEFT),
                new SeparatorMenuItem(),
                item("Full-screen", "Enter", state.fullScreen(), true, Keys.Action.FULL_SCREEN),
                new SeparatorMenuItem(),
                // Only once the selected image has finished loading (review R7, #34).
                item("Move to Trash…", "Del", false, state.canTrash(), Keys.Action.DELETE),
                new SeparatorMenuItem(),
                item("Settings…", "F12", false, true, Keys.Action.SETTINGS));
        menu.show(anchor, screenX, screenY);
    }

    void hide() {
        menu.hide();
    }

    /**
     * Closes the menu on a press anywhere in {@code scene} or when {@code window} loses focus to another one: the
     * menu's own auto-hide doesn't do it under XWayland (Marcos's check). That press, its release and its click go
     * nowhere else, as in other menus: a left click outside doesn't also move to another image.
     */
    void closeOnOutsidePress(Scene scene, Window window) {
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (menu.isShowing()) {
                menu.hide();
                swallowing = true;
                e.consume();
            } else {
                swallowing = false;   // no click came after the last swallowed press (released elsewhere)
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (swallowing) {
                e.consume();
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (swallowing) {
                e.consume();
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
            if (swallowing) {
                swallowing = false;   // the click comes last
                e.consume();
            }
        });
        window.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                menu.hide();
            }
        });
    }

    /** A row: a ✓ when {@code checked}, the name, and the key on the right. */
    private CustomMenuItem item(String name, String key, boolean checked, boolean enabled, Keys.Action action) {
        Label check = new Label(checked ? "✓" : "");
        check.setMinWidth(18);
        Label text = new Label(name);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);
        Label keyText = new Label(key);
        keyText.getStyleClass().add("menu-key");
        keyText.setMinWidth(Region.USE_PREF_SIZE);
        HBox row = new HBox(8, check, text, keyText);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinWidth(230);   // keys line up on the right
        // Only the row takes the click, not the item's padding around it: so the row carries the padding instead,
        // and a click anywhere in the highlighted row works (Marcos's check).
        row.setPadding(new Insets(4, 8, 4, 6));
        CustomMenuItem item = new CustomMenuItem(row, true);
        item.setStyle("-fx-padding: 0;");
        item.setDisable(!enabled);
        // After the menu has closed: a dialog opened while it is still closing (Settings…, the Trash question) could
        // take a second click to appear (Marcos's check).
        item.setOnAction(e -> Platform.runLater(() -> act.accept(action)));
        return item;
    }
}
