package linview.files;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import linview.image.Orientation;

/** #51: PNG and BMP written again turned, lossless, a PNG with its colour and text chunks. */
class TurnedImageTest {

    /** 3 × 2, every pixel different, one half transparent. */
    private static BufferedImage argb() {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < 6; i++) {
            image.setRGB(i % 3, i / 3, (i < 3 ? 0xff000000 : 0x80000000) | i * 0x102030);
        }
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, out));
        return out.toByteArray();
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    @Test
    void pngPixelsMatchTheTurnedViewWithTransparency() throws IOException {
        BufferedImage source = argb();
        for (int value = 1; value <= 8; value++) {
            Orientation orientation = orientationOf(value);
            BufferedImage back = ImageIO.read(new ByteArrayInputStream(
                    TurnedImage.turned(encode(source, "png"), "png", orientation)));
            assertEquals(orientation.swapsSides() ? 2 : 3, back.getWidth(), "orientation " + value);
            assertArrayEquals(orientation.turn(pixels(source), 3, 2), pixels(back), "orientation " + value);
        }
    }

    /** The file's own format stays: a palette PNG is still a palette PNG, 16-bit grey still 16-bit. */
    @Test
    void paletteAndBitDepthStay() throws IOException {
        BufferedImage indexed = new BufferedImage(3, 2, BufferedImage.TYPE_BYTE_INDEXED);
        BufferedImage turned = ImageIO.read(new ByteArrayInputStream(
                TurnedImage.turned(encode(indexed, "png"), "png", orientationOf(6))));
        assertInstanceOf(IndexColorModel.class, turned.getColorModel());

        BufferedImage grey16 = new BufferedImage(3, 2, BufferedImage.TYPE_USHORT_GRAY);
        grey16.getRaster().setSample(2, 0, 0, 65_000);
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(
                TurnedImage.turned(encode(grey16, "png"), "png", orientationOf(6))));
        assertEquals(16, back.getColorModel().getComponentSize(0));
        assertEquals(65_000, back.getRaster().getSample(1, 2, 0));   // top right → bottom right, turned right
    }

    @Test
    void bmpIsTurnedAndOneWithTransparencyIsRefused() throws IOException {
        BufferedImage rgb = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        rgb.setRGB(0, 0, 0x123456);
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(
                TurnedImage.turned(encode(rgb, "bmp"), "bmp", orientationOf(8))));
        assertEquals(2, back.getWidth());
        assertEquals(0x123456, back.getRGB(0, 2) & 0xffffff);   // top left → bottom left, turned left

        IOException e = assertThrows(IOException.class,
                () -> TurnedImage.turned(encode(argb(), "png"), "bmp", orientationOf(6)));
        assertEquals("a BMP with transparency can't be saved", e.getMessage());
    }

    /** Colour, text and pixel size are copied (x and y swap on a quarter turn); an EXIF orientation is dropped. */
    @Test
    void pngChunksAreKept() throws IOException {
        byte[] text = "Title\0Holiday".getBytes(StandardCharsets.ISO_8859_1);
        byte[] gamma = {0, 0, (byte) 0xb1, (byte) 0x8f};
        byte[] pixelSize = {0, 0, 0x0b, 0x13, 0, 0, 0x0e, (byte) 0xc4, 1};
        byte[] original = withChunks(encode(argb(), "png"),
                List.of("gAMA", "pHYs", "tEXt", "eXIf"), List.of(gamma, pixelSize, text, new byte[] {1, 2}));

        byte[] turned = TurnedImage.turned(original, "png", orientationOf(6));

        List<String> types = types(turned);
        assertEquals("IHDR", types.get(0));
        assertTrue(types.indexOf("gAMA") < types.indexOf("IDAT"));
        assertFalse(types.contains("eXIf"));
        assertArrayEquals(text, data(turned, "tEXt"));
        assertArrayEquals(gamma, data(turned, "gAMA"));
        assertArrayEquals(new byte[] {0, 0, 0x0e, (byte) 0xc4, 0, 0, 0x0b, 0x13, 1}, data(turned, "pHYs"));
        assertEquals(2, ImageIO.read(new ByteArrayInputStream(turned)).getWidth());   // still a valid PNG
    }

    private static Orientation orientationOf(int exifValue) {
        for (int degrees : new int[] {0, 90, 180, 270}) {
            for (boolean mirror : new boolean[] {false, true}) {
                Orientation o = new Orientation(degrees, mirror);
                if (o.exifValue() == exifValue) {
                    return o;
                }
            }
        }
        throw new IllegalArgumentException();
    }

    /** {@code png} with the given chunks right after its header. */
    private static byte[] withChunks(byte[] png, List<String> types, List<byte[]> data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int afterHeader = 8 + 12 + 13;
        out.write(png, 0, afterHeader);
        for (int i = 0; i < types.size(); i++) {
            byte[] type = types.get(i).getBytes(StandardCharsets.ISO_8859_1);
            CRC32 crc = new CRC32();
            crc.update(type);
            crc.update(data.get(i));
            writeInt(out, data.get(i).length);
            out.write(type, 0, 4);
            out.write(data.get(i), 0, data.get(i).length);
            writeInt(out, (int) crc.getValue());
        }
        out.write(png, afterHeader, png.length - afterHeader);
        return out.toByteArray();
    }

    private static List<String> types(byte[] png) {
        List<String> types = new ArrayList<>();
        for (int at = 8; at + 12 <= png.length; at += 12 + length(png, at)) {
            types.add(new String(png, at + 4, 4, StandardCharsets.ISO_8859_1));
        }
        return types;
    }

    private static byte[] data(byte[] png, String type) {
        for (int at = 8; at + 12 <= png.length; at += 12 + length(png, at)) {
            if (new String(png, at + 4, 4, StandardCharsets.ISO_8859_1).equals(type)) {
                return Arrays.copyOfRange(png, at + 8, at + 8 + length(png, at));
            }
        }
        throw new AssertionError("no " + type);
    }

    private static int length(byte[] b, int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | b[at + 3] & 0xFF;
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }
}
