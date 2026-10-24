package linview.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;

import org.junit.jupiter.api.Test;

/** R-FS-01: the window opens on the monitor under the mouse pointer; R-FS-02 (#26): at its remembered place there. */
class WindowPlacementTest {

    private static final Rectangle2D LEFT = new Rectangle2D(0, 0, 1920, 1080);
    private static final Rectangle2D RIGHT = new Rectangle2D(1920, 0, 1920, 1080);
    private static final List<Rectangle2D> SCREENS = List.of(LEFT, RIGHT);

    @Test
    void picksScreenUnderMouse() {
        assertEquals(RIGHT, WindowPlacement.screenAt(new Point2D(2500, 400), SCREENS, LEFT));
        assertEquals(LEFT, WindowPlacement.screenAt(new Point2D(100, 900), SCREENS, RIGHT));
    }

    @Test
    void fallsBackWhenMouseUnknownOrOffScreen() {
        assertEquals(LEFT, WindowPlacement.screenAt(null, SCREENS, LEFT));
        assertEquals(LEFT, WindowPlacement.screenAt(new Point2D(-50, -50), SCREENS, LEFT));
    }

    @Test
    void centresWindowOnScreen() {
        assertEquals(new Point2D(1920 + 448, 190), WindowPlacement.centredOn(RIGHT, 1024, 700));
    }

    /** R10 (Marcos 2026-10-24): closed on the left monitor, mouse there again: exactly where it was. */
    @Test
    void theSavedPlaceComesBackOnTheSameMonitor() {
        assertEquals(new Rectangle2D(200, 100, 1400, 900),
                WindowPlacement.startBounds(new Point2D(500, 500), SCREENS, LEFT, 200.0, 100.0, 1400, 900));
    }

    /** The mouse monitor wins: same size, centred on the monitor under the mouse. */
    @Test
    void onAnotherMonitorTheWindowIsCentredThere() {
        assertEquals(new Rectangle2D(1920 + 260, 90, 1400, 900),
                WindowPlacement.startBounds(new Point2D(2500, 500), SCREENS, LEFT, 200.0, 100.0, 1400, 900));
    }

    /** A place on a monitor that is gone is never used; a size larger than the screen shrinks to fit. */
    @Test
    void aPlaceOnAnUnpluggedMonitorIsNotUsed() {
        Rectangle2D laptop = new Rectangle2D(0, 0, 1366, 768);
        assertEquals(new Rectangle2D(0, 0, 1366, 768), WindowPlacement.startBounds(new Point2D(10, 10),
                List.of(laptop), laptop, 2200.0, 100.0, 1400, 900));
    }

    /** Nothing saved yet: centred on the mouse's monitor, as before #26. */
    @Test
    void withNothingSavedTheWindowIsCentred() {
        assertEquals(new Rectangle2D(1920 + 448, 190, 1024, 700),
                WindowPlacement.startBounds(new Point2D(2500, 500), SCREENS, LEFT, null, null, 1024, 700));
    }

    @Test
    void windowLargerThanScreenStartsAtScreenCorner() {
        assertEquals(new Point2D(1920, 0), WindowPlacement.centredOn(RIGHT, 2500, 1500));
    }
}
