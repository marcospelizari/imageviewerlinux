package linview.image;

/**
 * The result of loading one image: {@code image} with its stored size and the {@code orientation} that turns it
 * upright, or {@link #failed()} for a damaged or unsupported file. {@code I} is the image type (JavaFX
 * {@code Image} in the app), so code using it can be tested without starting JavaFX.
 */
public record Loaded<I>(I image, double width, double height, Orientation orientation) {

    /** A loaded image must have a real size; only {@link #failed()} has no image. */
    public Loaded {
        if (orientation == null) {
            throw new IllegalArgumentException("orientation is missing");
        }
        if (image != null && !(width > 0 && height > 0 && Double.isFinite(width) && Double.isFinite(height))) {
            throw new IllegalArgumentException("a loaded image needs a positive size: " + width + "×" + height);
        }
    }

    public static <I> Loaded<I> failed() {
        return new Loaded<>(null, 0, 0, Orientation.UPRIGHT);
    }

    public boolean isFailed() {
        return image == null;
    }

    /** Size of the image as shown upright, at actual size. */
    public double uprightWidth() {
        return orientation.swapsSides() ? height : width;
    }

    public double uprightHeight() {
        return orientation.swapsSides() ? width : height;
    }
}
