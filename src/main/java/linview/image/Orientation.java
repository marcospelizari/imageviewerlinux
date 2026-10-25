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

    /**
     * The inverse of {@link #turn} for one pixel: where pixel ({@code x}, {@code y}) of the upright image is in the
     * stored {@code width} × {@code height} image, as {@code y * width + x}. Lets the magnifier read a small upright
     * cut-out of a huge image without turning all of it (#60).
     */
    public int storedIndex(int x, int y, int width, int height) {
        int mx;
        int sy;
        switch (degrees) {
            case 90 -> {
                sy = height - 1 - x;
                mx = y;
            }
            case 180 -> {
                mx = width - 1 - x;
                sy = height - 1 - y;
            }
            case 270 -> {
                sy = x;
                mx = width - 1 - y;
            }
            default -> {
                mx = x;
                sy = y;
            }
        }
        return sy * width + (mirror ? width - 1 - mx : mx);
    }

    /**
     * {@code pixels} ({@code width} × {@code height}, row by row) turned upright, e.g. for a thumbnail file, which
     * is stored upright (#28); {@link #swapsSides} says whether the result is {@code height} wide.
     */
    public int[] turn(int[] pixels, int width, int height) {
        int outWidth = swapsSides() ? height : width;
        int[] out = new int[pixels.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int mx = mirror ? width - 1 - x : x;   // mirror first, then rotate clockwise
                int tx;
                int ty;
                switch (degrees) {
                    case 90 -> {
                        tx = height - 1 - y;
                        ty = mx;
                    }
                    case 180 -> {
                        tx = width - 1 - mx;
                        ty = height - 1 - y;
                    }
                    case 270 -> {
                        tx = y;
                        ty = width - 1 - mx;
                    }
                    default -> {
                        tx = mx;
                        ty = y;
                    }
                }
                out[ty * outWidth + tx] = pixels[y * width + x];
            }
        }
        return out;
    }
}
