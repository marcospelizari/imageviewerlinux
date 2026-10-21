package linview;

import java.util.List;

import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;

/**
 * Chooses where a new window goes: centred on the monitor under the mouse pointer (R-FS-01).
 * Pure geometry, so it can be tested without a screen.
 */
final class WindowPlacement {

    private WindowPlacement() {
    }

    /** The screen containing {@code mouse}, or {@code fallback} if the position is unknown or off all screens. */
    static Rectangle2D screenAt(Point2D mouse, List<Rectangle2D> screens, Rectangle2D fallback) {
        if (mouse != null) {
            for (Rectangle2D screen : screens) {
                if (screen.contains(mouse)) {
                    return screen;
                }
            }
        }
        return fallback;
    }

    /** Top-left corner that centres a window of the given size on {@code screen}, kept inside it. */
    static Point2D centredOn(Rectangle2D screen, double width, double height) {
        double x = screen.getMinX() + Math.max(0, (screen.getWidth() - width) / 2);
        double y = screen.getMinY() + Math.max(0, (screen.getHeight() - height) / 2);
        return new Point2D(x, y);
    }
}
