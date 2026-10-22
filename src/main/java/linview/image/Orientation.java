package linview.image;

import java.nio.file.Path;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;

/**
 * How to turn a stored image upright, from its EXIF orientation tag (R-FMT-03): mirror left-right first,
 * then rotate clockwise. No UI, so it can be tested without a screen.
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
     * Reads the orientation of {@code file}. No EXIF, a broken tag, a format without EXIF or an unreadable
     * file all give {@link #UPRIGHT}: the image is then shown as stored.
     */
    static Orientation read(Path file) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file.toFile());
            ExifIFD0Directory exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return of(exif.getInt(ExifIFD0Directory.TAG_ORIENTATION));
            }
        } catch (Exception e) {
            // Broken or missing metadata is common; showing the image as stored is the right fallback.
        }
        return UPRIGHT;
    }

    /** True when the upright image is the stored one turned sideways, so its width and height swap. */
    public boolean swapsSides() {
        return degrees == 90 || degrees == 270;
    }
}
