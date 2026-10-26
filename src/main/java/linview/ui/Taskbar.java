package linview.ui;

import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;

import linview.viewer.Zoom;

/**
 * The full-screen taskbar at the bottom-left (R-FS-04, #29, REPORT.md §3.3): ◀ ▶ − [52%] + Fit 100% Lock and the
 * image size, only as wide as its contents; Lock is highlighted while zoom lock is on. The buttons carry out the same actions as the keys. The % box takes {@code 52},
 * {@code 52%} or {@code 52.5}: Enter applies it, like + / − (Marcos, 2026-10-24), and anything else keeps the zoom;
 * Esc cancels the typing. While the box has the keyboard, the viewer's keys don't fire (§6.4). Settings opens the
 * settings window (#48).
 */
final class Taskbar extends HBox {

    private final TextField percent = new TextField();
    private final Label size = new Label();
    private final Button lock;
    private static final PseudoClass ON = PseudoClass.getPseudoClass("on");
    private String shownPercent = "";

    /** {@code act} carries out an action; {@code zoomTo} gets a typed %; {@code done} gives the keyboard back. */
    Taskbar(Consumer<Keys.Action> act, DoubleConsumer zoomTo, Runnable done) {
        getStyleClass().add("taskbar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(4);
        percent.getStyleClass().add("taskbar-percent");
        percent.setPrefColumnCount(5);
        percent.setMinWidth(USE_PREF_SIZE);
        // Only a click gives it the keyboard: else JavaFX focuses it when the bar appears, and the bar never hides.
        percent.setFocusTraversable(false);
        percent.setOnAction(e -> {
            Zoom.parsePercent(percent.getText()).ifPresent(zoomTo);
            percent.setText(shownPercent);   // the zoom shown, or the old one if the text was wrong
            done.run();
        });
        percent.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                percent.setText(shownPercent);
                e.consume();   // Esc here cancels the typing, it doesn't quit
                done.run();
            }
        });
        size.getStyleClass().add("taskbar-size");
        lock = button("Lock", "Zoom lock: every next image at this zoom", () -> act.accept(Keys.Action.ZOOM_LOCK));
        getChildren().addAll(button("◀", "Previous image", () -> act.accept(Keys.Action.PREVIOUS)),
                button("▶", "Next image", () -> act.accept(Keys.Action.NEXT)),
                button("−", "Zoom out", () -> act.accept(Keys.Action.ZOOM_OUT)),
                percent,
                button("+", "Zoom in", () -> act.accept(Keys.Action.ZOOM_IN)),
                button("Fit", "Fit to screen", () -> act.accept(Keys.Action.FIT)),
                button("100%", "Actual size", () -> act.accept(Keys.Action.ACTUAL_SIZE)),
                lock,
                button("Settings", "Settings (F12)", () -> act.accept(Keys.Action.SETTINGS)),
                size);
    }

    private static Button button(String text, String help, Runnable action) {
        Button button = new Button(text);
        button.setFocusTraversable(false);   // a click doesn't take the keyboard from the viewer
        button.setMinWidth(USE_PREF_SIZE);   // never squeezed to "…"
        button.setAccessibleText(help);
        button.setOnAction(e -> action.run());
        return button;
    }

    /** True while the % box has the keyboard: the viewer's keys must not fire, and the bar stays open. */
    boolean isTyping() {
        return percent.isFocused();
    }

    /** The image's size and zoom from the controller; empty when no image is shown. */
    void show(double width, double height, double zoom, boolean locked) {
        shownPercent = width > 0 ? format(zoom) + "%" : "";
        if (!isTyping()) {
            percent.setText(shownPercent);
        }
        size.setText(width > 0 ? String.format("%.0f × %.0f", width, height) : "");
        lock.pseudoClassStateChanged(ON, locked);
    }

    /** {@code 52} or {@code 52.5}, no {@code .0}. */
    private static String format(double zoom) {
        return zoom == Math.rint(zoom) ? String.valueOf((long) zoom) : String.valueOf(Math.round(zoom * 10) / 10.0);
    }
}
