package linview.files;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import linview.image.Orientation;

/**
 * A PNG or BMP written again turned upright (R-FILE-03, #51), lossless: pixels are moved in the file's own format, so
 * palette, bit depth and transparency stay. A PNG keeps its colour and text chunks (Marcos, 2026-10-26). No UI, so it
 * can be tested without a screen.
 */
final class TurnedImage {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    /** Copied from the original PNG: colour (profile, gamma), text, and pixel size; an EXIF orientation is not. */
    private static final Set<String> KEPT_CHUNKS = Set.of("iCCP", "sRGB", "gAMA", "cHRM", "tEXt", "zTXt", "iTXt",
            "pHYs");

    private TurnedImage() {
    }

    /**
     * {@code original} ({@code format} {@code png} or {@code bmp}) turned upright with {@code orientation}.
     *
     * @throws IOException if it can't be read, or Java can't write it back in its format (a BMP with transparency)
     */
    static byte[] turned(byte[] original, String format, Orientation orientation) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(original));
        if (source == null) {
            throw new IOException("the file is damaged or not a " + format.toUpperCase() + " image");
        }
        BufferedImage turned = turn(source, orientation);
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        if (!ImageIO.write(turned, format, written)) {
            throw new IOException(turned.getColorModel().hasAlpha()
                    ? "a " + format.toUpperCase() + " with transparency can't be saved"
                    : "this kind of " + format.toUpperCase() + " can't be saved");
        }
        return format.equals("png")
                ? withChunksOf(original, written.toByteArray(), orientation.swapsSides()) : written.toByteArray();
    }

    /** {@code source} turned upright, in its own colour model. */
    static BufferedImage turn(BufferedImage source, Orientation orientation) {
        ColorModel model = source.getColorModel();
        Raster in = source.getRaster();
        int width = in.getWidth();
        int height = in.getHeight();
        int outWidth = orientation.swapsSides() ? height : width;
        int outHeight = orientation.swapsSides() ? width : height;
        WritableRaster out = model.createCompatibleWritableRaster(outWidth, outHeight);
        Object pixel = null;
        for (int y = 0; y < outHeight; y++) {
            for (int x = 0; x < outWidth; x++) {
                int stored = orientation.storedIndex(x, y, width, height);
                pixel = in.getDataElements(stored % width, stored / width, pixel);
                out.setDataElements(x, y, pixel);
            }
        }
        return new BufferedImage(model, out, model.isAlphaPremultiplied(), null);
    }

    private record Chunk(String type, byte[] data) {
    }

    /**
     * {@code written} with the {@link #KEPT_CHUNKS} of {@code original} right after its header (where colour chunks
     * have to be), replacing any it has itself; a pixel size's x and y swap when the image was turned sideways.
     */
    static byte[] withChunksOf(byte[] original, byte[] written, boolean swapped) throws IOException {
        List<Chunk> kept = new ArrayList<>();
        for (Chunk chunk : chunks(original)) {
            if (KEPT_CHUNKS.contains(chunk.type())) {
                kept.add(chunk.type().equals("pHYs") && swapped ? swappedPixelSize(chunk) : chunk);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(written.length + 1024);
        out.write(PNG_SIGNATURE, 0, PNG_SIGNATURE.length);
        for (Chunk chunk : chunks(written)) {
            if (KEPT_CHUNKS.contains(chunk.type())) {
                continue;
            }
            writeChunk(out, chunk);
            if (chunk.type().equals("IHDR")) {
                kept.forEach(k -> writeChunk(out, k));
            }
        }
        return out.toByteArray();
    }

    private static Chunk swappedPixelSize(Chunk pHYs) {
        byte[] data = pHYs.data().clone();
        if (data.length == 9) {
            System.arraycopy(pHYs.data(), 4, data, 0, 4);
            System.arraycopy(pHYs.data(), 0, data, 4, 4);
        }
        return new Chunk("pHYs", data);
    }

    private static List<Chunk> chunks(byte[] png) throws IOException {
        if (png.length < PNG_SIGNATURE.length
                || !Arrays.equals(png, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)) {
            throw new IOException("not a PNG file");
        }
        List<Chunk> chunks = new ArrayList<>();
        int at = PNG_SIGNATURE.length;
        while (at + 12 <= png.length) {
            int length = readInt(png, at);
            if (length < 0 || at + 12L + length > png.length) {
                throw new IOException("damaged PNG file");
            }
            String type = new String(png, at + 4, 4, StandardCharsets.ISO_8859_1);
            chunks.add(new Chunk(type, Arrays.copyOfRange(png, at + 8, at + 8 + length)));
            at += 12 + length;
            if (type.equals("IEND")) {
                break;
            }
        }
        return chunks;
    }

    private static void writeChunk(ByteArrayOutputStream out, Chunk chunk) {
        byte[] type = chunk.type().getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(chunk.data());
        writeInt(out, chunk.data().length);
        out.write(type, 0, 4);
        out.write(chunk.data(), 0, chunk.data().length);
        writeInt(out, (int) crc.getValue());
    }

    private static int readInt(byte[] b, int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | b[at + 3] & 0xFF;
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }
}
