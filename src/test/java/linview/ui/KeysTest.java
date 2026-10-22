package linview.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import javafx.scene.input.KeyCode;

import linview.ui.Keys.Action;

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

    /** R-ZOOM-01: + is Shift+= on most layouts, so Shift is accepted there; the keypad works too. */
    @Test
    void zoomKeys() {
        assertEquals(Action.ZOOM_IN, plain(KeyCode.PLUS));
        assertEquals(Action.ZOOM_IN, Keys.actionFor(KeyCode.EQUALS, false, true, false));
        assertEquals(Action.ZOOM_IN, Keys.actionFor(KeyCode.PLUS, false, true, false));
        assertEquals(Action.ZOOM_IN, plain(KeyCode.ADD));
        assertEquals(Action.ZOOM_OUT, plain(KeyCode.MINUS));
        assertEquals(Action.ZOOM_OUT, plain(KeyCode.SUBTRACT));
        assertEquals(Action.FIT, plain(KeyCode.B));
        assertEquals(Action.FIT_WIDTH, plain(KeyCode.W));
        assertEquals(Action.FIT_OR_ACTUAL, plain(KeyCode.A));
        assertEquals(Action.ACTUAL_SIZE, plain(KeyCode.DIGIT1));
        assertEquals(Action.ACTUAL_SIZE, plain(KeyCode.NUMPAD1));
        assertNull(Keys.actionFor(KeyCode.B, false, true, false));   // Shift only where listed
        assertNull(plain(KeyCode.H));   // fit height has no key (Marcos, 2026-10-22)
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
