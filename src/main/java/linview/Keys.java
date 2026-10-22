package linview;

import static java.util.Map.entry;

import java.util.Map;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * Every keyboard shortcut, in one table (report §3.4). Keys copy FastStone; there is no config file for them.
 * No UI, so it can be tested without a screen.
 */
final class Keys {

    enum Action { NEXT, PREVIOUS, FIRST, LAST, OPEN, FULL_SCREEN, ESCAPE }

    private record Key(KeyCode code, boolean ctrl) {
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
            entry(new Key(KeyCode.ESCAPE, false), Action.ESCAPE));

    private Keys() {
    }

    static Action actionFor(KeyEvent e) {
        return actionFor(e.getCode(), e.isControlDown(), e.isShiftDown(), e.isAltDown());
    }

    /** The action for a key press, or {@code null} if the key has none. Shift and Alt are not used yet. */
    static Action actionFor(KeyCode code, boolean ctrl, boolean shift, boolean alt) {
        if (shift || alt) {
            return null;
        }
        return TABLE.get(new Key(code, ctrl));
    }
}
