package linview.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.exif.ExifIFD0Directory;

import linview.image.ImageHeader;

/** #51: the EXIF orientation of a JPEG changes, and nothing from the image data on. */
class JpegOrientationTest {

    @TempDir
    Path folder;

    /** A small JPEG as Java writes it: a JFIF block, no EXIF. */
    private static byte[] plainJpeg() throws IOException {
        BufferedImage image = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);
        image.setRGB(3, 4, 0xff0000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    /** From the first scan (FF DA) to the end: the image data, which must never change. */
    private static byte[] imageData(byte[] jpeg) {
        for (int i = 2; i < jpeg.length - 1; i++) {
            if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xDA) {
                return Arrays.copyOfRange(jpeg, i, jpeg.length);
            }
        }
        throw new AssertionError("no scan");
    }

    private int orientationOf(byte[] jpeg) throws IOException {
        Path file = Files.write(folder.resolve("t.jpg"), jpeg);
        return ImageHeader.read(file).orientation().exifValue();
    }

    private ExifIFD0Directory exifOf(byte[] jpeg) throws IOException, ImageProcessingException {
        Path file = Files.write(folder.resolve("t.jpg"), jpeg);
        return ImageMetadataReader.readMetadata(file.toFile()).getFirstDirectoryOfType(ExifIFD0Directory.class);
    }

    @Test
    void addsExifToAJpegWithoutAndThenChangesItInPlace() throws IOException {
        byte[] plain = plainJpeg();
        for (int value = 1; value <= 8; value++) {
            byte[] turned = JpegOrientation.withOrientation(plain, value);
            assertEquals(value, orientationOf(turned));
            assertArrayEquals(imageData(plain), imageData(turned));
            byte[] again = JpegOrientation.withOrientation(turned, 9 - value);
            assertEquals(9 - value, orientationOf(again));
            assertEquals(turned.length, again.length);   // the tag changed in place
            assertArrayEquals(imageData(plain), imageData(again));
        }
    }

    /** EXIF from a camera without the tag: it is added, and the camera's other tags stay readable. */
    @Test
    void addsTheTagToExistingExifInBothByteOrders() throws Exception {
        for (boolean little : new boolean[] {true, false}) {
            byte[] camera = withExif(plainJpeg(), little);
            assertEquals("Canon", exifOf(camera).getString(ExifIFD0Directory.TAG_MAKE));
            byte[] turned = JpegOrientation.withOrientation(camera, 6);
            ExifIFD0Directory exif = exifOf(turned);
            assertEquals(6, exif.getInt(ExifIFD0Directory.TAG_ORIENTATION));
            assertEquals("Canon", exif.getString(ExifIFD0Directory.TAG_MAKE));
            assertEquals("linview", exif.getString(ExifIFD0Directory.TAG_SOFTWARE));
            assertArrayEquals(imageData(camera), imageData(turned));
            assertEquals(3, orientationOf(JpegOrientation.withOrientation(turned, 3)));
        }
    }

    @Test
    void refusesWhatIsNotAJpeg() {
        assertThrows(IOException.class, () -> JpegOrientation.withOrientation(new byte[] {1, 2, 3, 4}, 6));
        assertThrows(IOException.class,
                () -> JpegOrientation.withOrientation(new byte[] {(byte) 0xFF, (byte) 0xD8, 0, 0, 0}, 6));
    }

    /**
     * {@code jpeg} with an EXIF block as a camera writes one, without orientation: Make (0x010F) and Software
     * (0x0131) in IFD0, both with their text stored after the IFD, so moving anything would break them.
     */
    private static byte[] withExif(byte[] jpeg, boolean little) throws IOException {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        tiff.write(little ? 'I' : 'M');
        tiff.write(little ? 'I' : 'M');
        write(tiff, 42, 2, little);
        write(tiff, 8, 4, little);
        write(tiff, 2, 2, little);                       // two entries
        int data = 8 + 2 + 2 * 12 + 4;                   // text after the IFD
        write(tiff, 0x010F, 2, little);
        write(tiff, 2, 2, little);
        write(tiff, 6, 4, little);
        write(tiff, data, 4, little);
        write(tiff, 0x0131, 2, little);
        write(tiff, 2, 2, little);
        write(tiff, 8, 4, little);
        write(tiff, data + 6, 4, little);
        write(tiff, 0, 4, little);                       // no next IFD
        tiff.write("Canon\0linview\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        byte[] body = tiff.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int afterJfif = 4 + ((jpeg[4] & 0xFF) << 8 | jpeg[5] & 0xFF);
        out.write(jpeg, 0, afterJfif);
        out.write(0xFF);
        out.write(0xE1);
        write(out, 2 + 6 + body.length, 2, false);
        out.write("Exif\0\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(body);
        out.write(jpeg, afterJfif, jpeg.length - afterJfif);
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, int value, int bytes, boolean little) {
        for (int i = 0; i < bytes; i++) {
            int shift = little ? 8 * i : 8 * (bytes - 1 - i);
            out.write(value >>> shift);
        }
    }
}
