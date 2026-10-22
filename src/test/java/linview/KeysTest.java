package linview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import javafx.scene.input.KeyCode;
import linview.Keys.Action;
import org.junit.jupiter.api.Test;

/** R-NAV-03: FastStone navigation keys, all in one table. */
class KeysTest {

    private static Action plain(KeyCode code) {
        return Keys.actionFor(code, false, false, false);
    }

    @Test
    void nextKeys() {
        assertEquals(Action.NEXT, plain(KeyCode.RIGHT));
        assertEquals(Action.NEXT, plain(KeyCode.PAGE_DOWN));
        assertEquals(Action.NEXT, plain(KeyCode.SPACE));
    }

    @Test
    void previousKeys() {
        assertEquals(Action.PREVIOUS, plain(KeyCode.LEFT));
        assertEquals(Action.PREVIOUS, plain(KeyCode.PAGE_UP));
        assertEquals(Action.PREVIOUS, plain(KeyCode.BACK_SPACE));
    }

    @Test
    void firstAndLast() {
        assertEquals(Action.FIRST, plain(KeyCode.HOME));
        assertEquals(Action.LAST, plain(KeyCode.END));
    }

    @Test
    void openWithOrWithoutCtrl() {
        assertEquals(Action.OPEN, plain(KeyCode.O));
        assertEquals(Action.OPEN, Keys.actionFor(KeyCode.O, true, false, false));
    }

    @Test
    void fullScreenAndEscape() {
        assertEquals(Action.FULL_SCREEN, plain(KeyCode.ENTER));
        assertEquals(Action.FULL_SCREEN, plain(KeyCode.F11));
        assertEquals(Action.ESCAPE, plain(KeyCode.ESCAPE));
    }

    @Test
    void otherKeysAndModifiersDoNothing() {
        assertNull(plain(KeyCode.Q));
        assertNull(Keys.actionFor(KeyCode.RIGHT, true, false, false));
        assertNull(Keys.actionFor(KeyCode.RIGHT, false, true, false));
        assertNull(Keys.actionFor(KeyCode.O, true, true, false));
        assertNull(Keys.actionFor(KeyCode.RIGHT, false, false, true));
    }
}
