package linview.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;

import org.junit.jupiter.api.Test;

/** R-FS-01: the window opens on the monitor under the mouse pointer. */
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

    @Test
    void windowLargerThanScreenStartsAtScreenCorner() {
        assertEquals(new Point2D(1920, 0), WindowPlacement.centredOn(RIGHT, 2500, 1500));
    }
}
