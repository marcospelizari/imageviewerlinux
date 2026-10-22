package linview.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import linview.viewer.Zoom;

/** R-SET-01: the setters are the one place that decides what a valid value is. */
class SettingsTest {

    private final Settings s = new Settings();

    @Test
    void zoomPercentStaysWithinTheZoomRange() {
        s.setZoomPercent(Zoom.MIN_PERCENT);
        s.setZoomPercent(Zoom.MAX_PERCENT);
        assertThrows(IllegalArgumentException.class, () -> s.setZoomPercent(201));
        assertThrows(IllegalArgumentException.class, () -> s.setZoomPercent(0.5));
        assertThrows(IllegalArgumentException.class, () -> s.setZoomPercent(Double.NaN));
        assertEquals(200, s.zoomPercent());   // a rejected value changes nothing
    }

    @Test
    void choicesCannotBeMissing() {
        assertThrows(IllegalArgumentException.class, () -> s.setZoomMode(null));
        assertThrows(IllegalArgumentException.class, () -> s.setWheel(null));
        assertEquals(Zoom.Mode.FIT, s.zoomMode());
    }

    @Test
    void backgroundIsAPresetOrAHexColourInLowerCase() {
        s.setBackground(" DarkGrey ");
        assertEquals("darkgrey", s.background());
        s.setBackground("#A0B1C2");
        assertEquals("#a0b1c2", s.background());
        assertThrows(IllegalArgumentException.class, () -> s.setBackground("#12345"));
        assertThrows(IllegalArgumentException.class, () -> s.setBackground("pink"));
        assertThrows(IllegalArgumentException.class, () -> s.setBackground(null));
        assertEquals("#a0b1c2", s.background());
    }

    @Test
    void windowPositionIsBothOrNeither() {
        s.setWindowPosition(10.0, 20.0);
        assertThrows(IllegalArgumentException.class, () -> s.setWindowPosition(10.0, null));
        assertThrows(IllegalArgumentException.class, () -> s.setWindowPosition(Double.NaN, 1.0));
        assertEquals(10.0, s.windowX());
        s.setWindowPosition(null, null);
        assertNull(s.windowY());
    }

    @Test
    void windowSizeHasAMinimum() {
        assertThrows(IllegalArgumentException.class, () -> s.setWindowWidth(199));
        assertThrows(IllegalArgumentException.class, () -> s.setWindowHeight(Double.POSITIVE_INFINITY));
        s.setWindowWidth(200);
        assertEquals(200, s.windowWidth());
    }
}
