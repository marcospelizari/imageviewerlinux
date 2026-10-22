package linview.ui;

import static java.util.Map.entry;

import java.util.Map;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * Every keyboard shortcut, in one table (report §3.4). Keys copy FastStone; there is no config file for them.
 * No UI, so it can be tested without a screen.
 */
public final class Keys {

    public enum Action {
        NEXT, PREVIOUS, FIRST, LAST, OPEN, FULL_SCREEN, ESCAPE,
        ZOOM_IN, ZOOM_OUT, FIT, FIT_WIDTH, ACTUAL_SIZE, FIT_OR_ACTUAL
    }

    private record Key(KeyCode code, boolean ctrl, boolean shift) {

        Key(KeyCode code, boolean ctrl) {
            this(code, ctrl, false);
        }
    }

    private static final Map<Key, Action> TABLE = Map.ofEntries(
            entry(new Key(KeyCode.RIGHT, false), Action.NEXT),
            entry(new Key(KeyCode.PAGE_DOWN, false), Action.NEXT),
            entry(new Key(KeyCode.SPACE, false), Action.NEXT),
            entry(new Key(KeyCode.LEFT, false), Action.PREVIOUS),
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
            entry(new Key(KeyCode.B, false), Action.FIT),
            entry(new Key(KeyCode.W, false), Action.FIT_WIDTH),
            entry(new Key(KeyCode.A, false), Action.FIT_OR_ACTUAL),
            entry(new Key(KeyCode.DIGIT1, false), Action.ACTUAL_SIZE),
            entry(new Key(KeyCode.NUMPAD1, false), Action.ACTUAL_SIZE));

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
