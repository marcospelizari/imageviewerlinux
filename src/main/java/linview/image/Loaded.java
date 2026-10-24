package linview.image;

/**
 * The result of loading one image: {@code image} with the file's real (stored) size and the {@code orientation} that
 * turns it upright, or {@link #failed()} for a damaged or unsupported file. Zoom, title and actual size always use
 * the real size. {@code image} may be a smaller copy decoded for the screen (#19): {@code resolution} is its pixels
 * per real pixel, 1 for the full image. {@code loadMillis} is how long loading took, for the timing log (#23); 0
 * when not measured. {@code I} is the image type (JavaFX {@code Image} in the app), so code using it can be tested
 * without starting JavaFX.
 */
public record Loaded<I>(I image, double width, double height, Orientation orientation, double resolution,
                        double loadMillis) {

    /** A loaded image must have a real size and a resolution up to full; only {@link #failed()} has no image. */
    public Loaded {
        if (orientation == null) {
            throw new IllegalArgumentException("orientation is missing");
        }
        if (image != null && !(width > 0 && height > 0 && Double.isFinite(width) && Double.isFinite(height))) {
            throw new IllegalArgumentException("a loaded image needs a positive size: " + width + "×" + height);
        }
        if (image != null && !(resolution > 0 && resolution <= 1)) {
            throw new IllegalArgumentException("resolution must be above 0 and at most 1: " + resolution);
        }
    }

    /** The full image; load time not measured. */
    public Loaded(I image, double width, double height, Orientation orientation) {
        this(image, width, height, orientation, 1, 0);
    }

    public static <I> Loaded<I> failed() {
        return new Loaded<>(null, 0, 0, Orientation.UPRIGHT);
    }

    public boolean isFailed() {
        return image == null;
    }

    /** The same image turned a further {@code quarterTurns} × 90° clockwise (R-VIEW-01). */
    public Loaded<I> rotated(int quarterTurns) {
        return new Loaded<>(image, width, height, orientation.rotated(quarterTurns), resolution, loadMillis);
    }

    /** The same result with its load time; a failed result stays failed. */
    public Loaded<I> timed(double millis) {
        return isFailed() ? this : new Loaded<>(image, width, height, orientation, resolution, millis);
    }

    /** {@code full} in place of this screen-size copy (#22), keeping this one's orientation, e.g. a rotation. */
    public Loaded<I> withImage(Loaded<I> full) {
        return new Loaded<>(full.image, width, height, orientation, full.resolution, loadMillis);
    }

    /** Size of the image as shown upright, at actual size. */
    public double uprightWidth() {
        return orientation.swapsSides() ? height : width;
    }

    public double uprightHeight() {
        return orientation.swapsSides() ? width : height;
    }
}
