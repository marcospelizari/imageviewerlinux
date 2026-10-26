package linview.ui;

import static java.util.Map.entry;

import java.util.List;
import java.util.Map;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * Every keyboard shortcut, in one table (report §3.4). Keys copy FastStone; there is no config file for them.
 * No UI, so it can be tested without a screen.
 */
public final class Keys {

    public enum Action {
        NEXT, PREVIOUS, FIRST, LAST, OPEN, FULL_SCREEN, ESCAPE, ARROW_LEFT, ARROW_RIGHT, ARROW_UP, ARROW_DOWN,
        ZOOM_IN, ZOOM_OUT, FIT, FIT_WIDTH, FIT_HEIGHT, ACTUAL_SIZE, ZOOM_200, FIT_OR_ACTUAL, ZOOM_LOCK, ROTATE_RIGHT,
        ROTATE_LEFT, DELETE, SETTINGS, SHORTCUTS
    }

    // FIT_HEIGHT has no key (Marcos, 2026-10-22): the menu (#49) and the settings window (#48) choose it.
    // FIT has none either: A already toggles fit ↔ actual size (Marcos, 2026-10-26, #36); menu and taskbar have it.

    private record Key(KeyCode code, boolean ctrl, boolean shift) {

        Key(KeyCode code, boolean ctrl) {
            this(code, ctrl, false);
        }
    }

    private static final Map<Key, Action> TABLE = Map.ofEntries(
            // Arrows scroll an image larger than the window, else ← → go to the previous / next image (FastStone, #36).
            entry(new Key(KeyCode.RIGHT, false), Action.ARROW_RIGHT),
            entry(new Key(KeyCode.LEFT, false), Action.ARROW_LEFT),
            entry(new Key(KeyCode.UP, false), Action.ARROW_UP),
            entry(new Key(KeyCode.DOWN, false), Action.ARROW_DOWN),
            entry(new Key(KeyCode.PAGE_DOWN, false), Action.NEXT),
            entry(new Key(KeyCode.SPACE, false), Action.NEXT),
            entry(new Key(KeyCode.PAGE_UP, false), Action.PREVIOUS),
            entry(new Key(KeyCode.BACK_SPACE, false), Action.PREVIOUS),
            entry(new Key(KeyCode.HOME, false), Action.FIRST),
            entry(new Key(KeyCode.END, false), Action.LAST),
            entry(new Key(KeyCode.O, true), Action.OPEN),
            entry(new Key(KeyCode.O, false), Action.OPEN),
            entry(new Key(KeyCode.ENTER, false), Action.FULL_SCREEN),   // FastStone
            entry(new Key(KeyCode.F11, false), Action.FULL_SCREEN),     // usual Linux/Windows key
            entry(new Key(KeyCode.ESCAPE, false), Action.ESCAPE),
            // + is Shift+= on most layouts; the keypad has its own keys.
            entry(new Key(KeyCode.PLUS, false), Action.ZOOM_IN),
            entry(new Key(KeyCode.PLUS, false, true), Action.ZOOM_IN),
            entry(new Key(KeyCode.EQUALS, false), Action.ZOOM_IN),
            entry(new Key(KeyCode.EQUALS, false, true), Action.ZOOM_IN),
            entry(new Key(KeyCode.ADD, false), Action.ZOOM_IN),
            entry(new Key(KeyCode.MINUS, false), Action.ZOOM_OUT),
            entry(new Key(KeyCode.SUBTRACT, false), Action.ZOOM_OUT),
            entry(new Key(KeyCode.W, false), Action.FIT_WIDTH),
            entry(new Key(KeyCode.A, false), Action.FIT_OR_ACTUAL),
            entry(new Key(KeyCode.DIGIT1, false), Action.ACTUAL_SIZE),
            entry(new Key(KeyCode.NUMPAD1, false), Action.ACTUAL_SIZE),
            // FastStone's 1–9 are 100–900%; linview zooms up to 200%, so 3–9 have no key (Marcos, 2026-10-26).
            entry(new Key(KeyCode.DIGIT2, false), Action.ZOOM_200),
            entry(new Key(KeyCode.NUMPAD2, false), Action.ZOOM_200),
            entry(new Key(KeyCode.L, true, true), Action.ZOOM_LOCK),    // FastStone: "Lock zoom ratio"
            entry(new Key(KeyCode.R, false), Action.ROTATE_RIGHT),
            entry(new Key(KeyCode.L, false), Action.ROTATE_LEFT),
            entry(new Key(KeyCode.DELETE, false), Action.DELETE),                 // to the Trash (#34)
            entry(new Key(KeyCode.F12, false), Action.SETTINGS),                  // FastStone (#48)
            entry(new Key(KeyCode.F1, false), Action.SHORTCUTS));                 // the list below (#36)

    /** One line of the shortcut list (F1, #36). */
    record Shortcut(String keys, String action) {
    }

    /** What F1 shows, mouse included. Keep it in step with {@link #TABLE} and the README's key list. */
    static final List<Shortcut> LIST = List.of(
            new Shortcut("→  /  ←", "Next / previous image; scrolls an image wider than the window"),
            new Shortcut("↓  /  ↑", "Scrolls an image taller than the window"),
            new Shortcut("PgDn, Space  /  PgUp, Backspace", "Next / previous image"),
            new Shortcut("Home  /  End", "First / last image"),
            new Shortcut("Ctrl+O or O", "Open an image or a folder"),
            new Shortcut("Enter or F11", "Full-screen on / off"),
            new Shortcut("Esc", "Quit, also from full-screen"),
            new Shortcut("+  /  −", "Zoom in / out"),
            new Shortcut("W", "Fit width"),
            new Shortcut("A", "Fit to window ↔ actual size"),
            new Shortcut("1  /  2", "Actual size (100%)  /  200%"),
            new Shortcut("Ctrl+Shift+L", "Zoom lock on / off"),
            new Shortcut("R  /  L", "Rotate right / left"),
            new Shortcut("Del", "Move to the Trash"),
            new Shortcut("F12", "Settings"),
            new Shortcut("F1", "This list"),
            new Shortcut("Mouse wheel", "Scroll the image, or next / previous image (Settings)"),
            new Shortcut("Ctrl or Shift + wheel", "Zoom at the mouse pointer"),
            new Shortcut("Click right / left half", "Next / previous image"),
            new Shortcut("Hold left button", "Magnifier, when the image fits the window"),
            new Shortcut("Drag", "Move an image larger than the window"),
            new Shortcut("Right-click", "Menu"));

    private Keys() {
    }

    public static Action actionFor(KeyEvent e) {
        return actionFor(e.getCode(), e.isControlDown(), e.isShiftDown(), e.isAltDown());
    }

    /** The action for a key press, or {@code null} if the key has none. Alt is not used yet. */
    static Action actionFor(KeyCode code, boolean ctrl, boolean shift, boolean alt) {
        if (alt) {
            return null;
        }
        return TABLE.get(new Key(code, ctrl, shift));
    }
}
