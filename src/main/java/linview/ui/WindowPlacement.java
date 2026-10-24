package linview.ui;

import java.util.List;

import javafx.geometry.Dimension2D;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.robot.Robot;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * Chooses where a new window goes: on the monitor under the mouse pointer (R-FS-01), at its remembered place if that
 * is the monitor it was closed on (R-FS-02, #26), else centred. Pure geometry, so it can be tested without a screen.
 */
public final class WindowPlacement {

    private WindowPlacement() {
    }

    /**
     * Where the window opens (#26, R10 decided by Marcos 2026-10-24): the monitor under the mouse wins. On the
     * monitor the window was closed on (its saved centre is there) it comes back at its saved place; on another one,
     * or with nothing saved, it is centred. The size shrinks to fit that monitor, and the window stays on it, so a
     * place on an unplugged monitor is never used.
     *
     * @param x {@code null} when no place is saved
     * @return the window's top-left corner and size
     */
    static Rectangle2D startBounds(Point2D mouse, List<Rectangle2D> screens, Rectangle2D fallback,
                                   Double x, Double y, double width, double height) {
        Rectangle2D screen = screenAt(mouse, screens, fallback);
        double w = Math.min(width, screen.getWidth());
        double h = Math.min(height, screen.getHeight());
        if (x != null && y != null && screen.contains(x + width / 2, y + height / 2)) {
            double left = Math.max(screen.getMinX(), Math.min(x, screen.getMaxX() - w));
            double top = Math.max(screen.getMinY(), Math.min(y, screen.getMaxY() - h));
            return new Rectangle2D(left, top, w, h);
        }
        Point2D corner = centredOn(screen, w, h);
        return new Rectangle2D(corner.getX(), corner.getY(), w, h);
    }

    /**
     * {@link #startBounds} for this desktop. Without an explicit position JavaFX centres on the primary monitor,
     * which is why the place is always set.
     *
     * @return the size the window's content should have
     */
    public static Dimension2D placeAtStart(Stage stage, Double x, Double y, double width, double height) {
        List<Rectangle2D> screens = Screen.getScreens().stream().map(Screen::getVisualBounds).toList();
        Rectangle2D bounds = startBounds(new Robot().getMousePosition(), screens,
                Screen.getPrimary().getVisualBounds(), x, y, width, height);
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        return new Dimension2D(bounds.getWidth(), bounds.getHeight());
    }

    /**
     * The largest screen in physical pixels, width and height each the largest of any screen: what a screen-size
     * image must cover wherever the window goes (#19). With desktop scaling one JavaFX pixel is several physical ones.
     */
    public static Dimension2D largestScreenPixels() {
        double width = 0;
        double height = 0;
        for (Screen screen : Screen.getScreens()) {
            width = Math.max(width, screen.getBounds().getWidth() * screen.getOutputScaleX());
            height = Math.max(height, screen.getBounds().getHeight() * screen.getOutputScaleY());
        }
        return new Dimension2D(width, height);
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
