package linview.viewer;

/**
 * Zoom calculations (R-ZOOM-01, R-ZOOM-03, R-ZOOM-04): the scale for a zoom mode and where a new image starts.
 * No UI, so it can be tested without a screen. Sizes are of the upright image (after EXIF orientation).
 * Zooming with + / − changes only the image on screen; the next image starts again from the mode (decided
 * 2026-10-22), so nothing here remembers a manual zoom.
 */
public final class Zoom {

    public enum Mode { FIT, WIDTH, HEIGHT, ACTUAL, LOCK }

    /**
     * Image drawn at {@code scale} with its top-left corner at ({@code x}, {@code y}) in the view;
     * negative = scrolled.
     */
    public record View(double scale, double x, double y) {
    }

    private Zoom() {
    }

    /**
     * Scale for {@code mode}: 1 = actual size. {@code lockedPercent} is used only by {@link Mode#LOCK}. The fit
     * modes only shrink: an image smaller than the view keeps its real size (decided 2026-10-22).
     */
    static double scale(Mode mode, double lockedPercent,
                        double imageWidth, double imageHeight, double viewWidth, double viewHeight) {
        requirePositive(imageWidth, imageHeight, viewWidth, viewHeight);
        double fit = switch (mode) {
            case FIT -> Math.min(viewWidth / imageWidth, viewHeight / imageHeight);
            case WIDTH -> viewWidth / imageWidth;
            case HEIGHT -> viewHeight / imageHeight;
            case ACTUAL -> 1;
            case LOCK -> lockedPercent / 100;
        };
        boolean fitMode = mode == Mode.FIT || mode == Mode.WIDTH || mode == Mode.HEIGHT;
        return fitMode ? Math.min(1, fit) : fit;
    }

    /**
     * Where a new image starts at {@code scale}: centred on each side where it fits; if taller than the view,
     * at its top; if wider, centred, so a large image opens at its top-centre (R-ZOOM-03).
     */
    static View start(double scale, double imageWidth, double imageHeight, double viewWidth, double viewHeight) {
        requirePositive(imageWidth, imageHeight, viewWidth, viewHeight);
        double width = imageWidth * scale;
        double height = imageHeight * scale;
        double x = (viewWidth - width) / 2;
        double y = height <= viewHeight ? (viewHeight - height) / 2 : 0;
        return new View(scale, x, y);
    }

    /** Zoom range in % for + / − and for the locked zoom; more than 200% shows too little of a small image. */
    public static final double MIN_PERCENT = 1;
    public static final double MAX_PERCENT = 200;

    /** Zoom steps for + / −, in %, from {@link #MIN_PERCENT} to {@link #MAX_PERCENT}. */
    static final double[] STEPS = {1, 2, 3, 5, 8, 10, 15, 20, 25, 33, 50, 67, 75, 100, 125, 150, 200};

    /** The next step above {@code scale}, or the largest one. */
    static double zoomIn(double scale) {
        for (double step : STEPS) {
            if (step / 100 > scale * (1 + 1e-9)) {
                return step / 100;
            }
        }
        return STEPS[STEPS.length - 1] / 100;
    }

    /** The next step below {@code scale}, or the smallest one. */
    static double zoomOut(double scale) {
        for (int i = STEPS.length - 1; i >= 0; i--) {
            if (STEPS[i] / 100 < scale * (1 - 1e-9)) {
                return STEPS[i] / 100;
            }
        }
        return STEPS[0] / 100;
    }

    /**
     * {@code view} changed to {@code scale}, keeping the image point under ({@code anchorX}, {@code anchorY}) in
     * place. Then each side is kept tidy: centred where the image fits, no empty band where it doesn't. With an
     * unchanged scale this only tidies, e.g. after the window was resized.
     */
    static View around(View view, double scale, double anchorX, double anchorY,
                       double imageWidth, double imageHeight, double viewWidth, double viewHeight) {
        requirePositive(imageWidth, imageHeight, viewWidth, viewHeight);
        double k = scale / view.scale();
        double x = place(anchorX - (anchorX - view.x()) * k, imageWidth * scale, viewWidth);
        double y = place(anchorY - (anchorY - view.y()) * k, imageHeight * scale, viewHeight);
        return new View(scale, x, y);
    }

    private static double place(double position, double size, double viewSize) {
        if (size <= viewSize) {
            return (viewSize - size) / 2;
        }
        return Math.max(viewSize - size, Math.min(0, position));
    }

    private static void requirePositive(double... sizes) {
        for (double size : sizes) {
            if (!(size > 0)) {
                throw new IllegalArgumentException("sizes must be positive: " + size);
            }
        }
    }
}
