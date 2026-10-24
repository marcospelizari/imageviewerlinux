package linview.image;

/**
 * The result of loading one image: {@code image} with its stored size and the {@code orientation} that turns it
 * upright, or {@link #failed()} for a damaged or unsupported file. {@code loadMillis} is how long loading took,
 * for the timing log (#23); 0 when not measured. {@code I} is the image type (JavaFX {@code Image} in the app), so
 * code using it can be tested without starting JavaFX.
 */
public record Loaded<I>(I image, double width, double height, Orientation orientation, double loadMillis) {

    /** A loaded image must have a real size; only {@link #failed()} has no image. */
    public Loaded {
        if (orientation == null) {
            throw new IllegalArgumentException("orientation is missing");
        }
        if (image != null && !(width > 0 && height > 0 && Double.isFinite(width) && Double.isFinite(height))) {
            throw new IllegalArgumentException("a loaded image needs a positive size: " + width + "×" + height);
        }
    }

    /** Load time not measured. */
    public Loaded(I image, double width, double height, Orientation orientation) {
        this(image, width, height, orientation, 0);
    }

    public static <I> Loaded<I> failed() {
        return new Loaded<>(null, 0, 0, Orientation.UPRIGHT);
    }

    public boolean isFailed() {
        return image == null;
    }

    /** The same image turned a further {@code quarterTurns} × 90° clockwise (R-VIEW-01). */
    public Loaded<I> rotated(int quarterTurns) {
        return new Loaded<>(image, width, height, orientation.rotated(quarterTurns), loadMillis);
    }

    /** The same result with its load time; a failed result stays failed. */
    public Loaded<I> timed(double millis) {
        return isFailed() ? this : new Loaded<>(image, width, height, orientation, millis);
    }

    /** Size of the image as shown upright, at actual size. */
    public double uprightWidth() {
        return orientation.swapsSides() ? height : width;
    }

    public double uprightHeight() {
        return orientation.swapsSides() ? width : height;
    }
}
