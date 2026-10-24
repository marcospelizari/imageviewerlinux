package linview.image;

/**
 * How to turn a stored image upright, from its EXIF orientation tag (R-FMT-03): mirror left-right first,
 * then rotate clockwise. {@link ImageHeader} reads the tag. No UI, so it can be tested without a screen.
 */
public record Orientation(int degrees, boolean mirror) {

    public static final Orientation UPRIGHT = new Orientation(0, false);

    /** EXIF values 1–8 (same table as exiftool); anything else is treated as upright. */
    static Orientation of(int exifValue) {
        return switch (exifValue) {
            case 2 -> new Orientation(0, true);     // mirror horizontal
            case 3 -> new Orientation(180, false);  // rotate 180
            case 4 -> new Orientation(180, true);   // mirror vertical
            case 5 -> new Orientation(270, true);   // mirror horizontal, rotate 270 CW
            case 6 -> new Orientation(90, false);   // rotate 90 CW
            case 7 -> new Orientation(90, true);    // mirror horizontal, rotate 90 CW
            case 8 -> new Orientation(270, false);  // rotate 270 CW
            default -> UPRIGHT;
        };
    }

    /**
     * This orientation turned a further {@code quarterTurns} × 90° clockwise (negative = anticlockwise), as the
     * R / L view rotation does (R-VIEW-01). The rotation comes after the mirror, so it simply adds up.
     */
    public Orientation rotated(int quarterTurns) {
        return new Orientation(Math.floorMod(degrees + 90 * quarterTurns, 360), mirror);
    }

    /** True when the upright image is the stored one turned sideways, so its width and height swap. */
    public boolean swapsSides() {
        return degrees == 90 || degrees == 270;
    }
}
