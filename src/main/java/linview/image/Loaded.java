package linview.image;

/**
 * The result of loading one image: {@code image} with the file's real (stored) size and the {@code orientation} that
 * turns it upright, or {@link #failed()} for a damaged or unsupported file, or {@link #tooLarge} for one that needs
 * more memory to decode than linview allows (#20). Zoom, title and actual size always use the real size.
 * {@code image} may be a smaller copy decoded for the screen (#19): {@code resolution} is its pixels per real pixel,
 * 1 for the full image. {@code loadMillis} is how long loading took, for the timing log (#23); 0 when not measured.
 * {@code cached} says it came from the cache (#20). {@code stamp} is the file's state when loading began (a
 * {@link FileStamp} in the app, {@code null} when unknown): saving a rotation refuses a file changed since (#69).
 * {@code I} is the image type (JavaFX {@code Image} in the app), so
 * code using it can be tested without starting JavaFX.
 */
public record Loaded<I>(I image, double width, double height, Orientation orientation, double resolution,
                        double loadMillis, boolean cached, Object stamp) {

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

    /** Freshly loaded, not from the cache; no stamp yet. */
    public Loaded(I image, double width, double height, Orientation orientation, double resolution,
                  double loadMillis) {
        this(image, width, height, orientation, resolution, loadMillis, false, null);
    }

    /** The full image; load time not measured. */
    public Loaded(I image, double width, double height, Orientation orientation) {
        this(image, width, height, orientation, 1, 0);
    }

    public static <I> Loaded<I> failed() {
        return new Loaded<>(null, 0, 0, Orientation.UPRIGHT);
    }

    /** A {@code width} × {@code height} image (as stored) that needs too much memory to decode (#20). */
    public static <I> Loaded<I> tooLarge(double width, double height) {
        return new Loaded<>(null, width, height, Orientation.UPRIGHT);
    }

    /** Damaged, unsupported or {@link #isTooLarge too large}: nothing to show. */
    public boolean isFailed() {
        return image == null;
    }

    public boolean isTooLarge() {
        return image == null && width > 0 && height > 0;
    }

    /** Megapixels as stored, e.g. 317 for the "too large" message. */
    public long megapixels() {
        return Math.round(width * height / 1e6);
    }

    /** The same image turned a further {@code quarterTurns} × 90° clockwise (R-VIEW-01). */
    public Loaded<I> rotated(int quarterTurns) {
        return new Loaded<>(image, width, height, orientation.rotated(quarterTurns), resolution, loadMillis, cached,
                stamp);
    }

    /** The same result with its load time; a failed result stays failed. */
    public Loaded<I> timed(double millis) {
        return isFailed() ? this : new Loaded<>(image, width, height, orientation, resolution, millis, cached, stamp);
    }

    /** The same result taken from the cache: no load time. */
    public Loaded<I> fromCache() {
        return isFailed() ? this : new Loaded<>(image, width, height, orientation, resolution, 0, true, stamp);
    }

    /**
     * {@code full} in place of this screen-size copy (#22), keeping this one's orientation, e.g. a rotation. The file
     * state is {@code full}'s, since the pixels are (#74): a file replaced in between by one of the same size.
     */
    public Loaded<I> withImage(Loaded<I> full) {
        return new Loaded<>(full.image, width, height, orientation, full.resolution, loadMillis, cached, full.stamp);
    }

    /** The same result with the file's state when loading began. */
    public Loaded<I> stamped(Object state) {
        return new Loaded<>(image, width, height, orientation, resolution, loadMillis, cached, state);
    }

    /** Size of the image as shown upright, at actual size. */
    public double uprightWidth() {
        return orientation.swapsSides() ? height : width;
    }

    public double uprightHeight() {
        return orientation.swapsSides() ? width : height;
    }
}
