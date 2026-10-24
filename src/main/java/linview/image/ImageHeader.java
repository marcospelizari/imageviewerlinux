package linview.image;

import java.nio.file.Path;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.bmp.BmpHeaderDirectory;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.gif.GifHeaderDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.png.PngDirectory;

/**
 * What a file's header says before decoding: the EXIF orientation (R-FMT-03) and the stored size (#19), read in one
 * pass with metadata-extractor, which is pure Java. Java's own JPEG reader is not used for this: in the distro JDK it
 * needs the system's {@code libjpeg.so.8}, which other distributions don't have, so the packaged app failed there
 * (#21). No UI, so it can be tested without a screen.
 *
 * @param width  the stored width; 0 when the header doesn't say (the image then loads whole)
 * @param height the stored height; 0 likewise
 */
record ImageHeader(Orientation orientation, double width, double height) {

    static final ImageHeader UNKNOWN = new ImageHeader(Orientation.UPRIGHT, 0, 0);

    boolean hasSize() {
        return width > 0 && height > 0;
    }

    /**
     * Reads {@code file}'s header. No EXIF, a broken tag, a format without EXIF or an unreadable file all give
     * {@link Orientation#UPRIGHT}; a size it can't find gives 0.
     */
    static ImageHeader read(Path file) {
        Metadata metadata;
        try {
            metadata = ImageMetadataReader.readMetadata(file.toFile());
        } catch (Exception e) {
            // Broken or missing metadata is common; showing the image as stored is the right fallback.
            return UNKNOWN;
        }
        return new ImageHeader(orientation(metadata), size(metadata, true), size(metadata, false));
    }

    private static Orientation orientation(Metadata metadata) {
        try {
            ExifIFD0Directory exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return Orientation.of(exif.getInt(ExifIFD0Directory.TAG_ORIENTATION));
            }
        } catch (Exception e) {
            // A broken tag: shown as stored.
        }
        return Orientation.UPRIGHT;
    }

    /** From the first JPEG, PNG, GIF or BMP header with it; a BMP stored top-down has a negative height. */
    private static double size(Metadata metadata, boolean width) {
        for (Directory directory : metadata.getDirectories()) {
            Integer value = null;
            if (directory instanceof JpegDirectory) {
                value = directory.getInteger(width ? JpegDirectory.TAG_IMAGE_WIDTH : JpegDirectory.TAG_IMAGE_HEIGHT);
            } else if (directory instanceof PngDirectory) {
                value = directory.getInteger(width ? PngDirectory.TAG_IMAGE_WIDTH : PngDirectory.TAG_IMAGE_HEIGHT);
            } else if (directory instanceof GifHeaderDirectory) {
                value = directory.getInteger(
                        width ? GifHeaderDirectory.TAG_IMAGE_WIDTH : GifHeaderDirectory.TAG_IMAGE_HEIGHT);
            } else if (directory instanceof BmpHeaderDirectory) {
                value = directory.getInteger(
                        width ? BmpHeaderDirectory.TAG_IMAGE_WIDTH : BmpHeaderDirectory.TAG_IMAGE_HEIGHT);
            }
            if (value != null && value != 0) {
                return Math.abs(value);
            }
        }
        return 0;
    }
}
