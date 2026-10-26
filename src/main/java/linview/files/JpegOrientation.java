package linview.files;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Sets the EXIF orientation tag of a JPEG without touching its image data (R-FILE-03, #51): the bytes from the first
 * scan on stay identical, so nothing is decoded or re-encoded. Own code rather than a library (Marcos, 2026-10-26).
 * No UI, so it can be tested without a screen.
 */
final class JpegOrientation {

    private static final int ORIENTATION = 0x0112;
    private static final int SHORT = 3;
    private static final byte[] EXIF = {'E', 'x', 'i', 'f', 0, 0};

    private JpegOrientation() {
    }

    /**
     * {@code jpeg} with orientation {@code value} (1–8). An existing tag is changed in place; a missing one is added
     * in a copy of the first IFD appended to the EXIF block, so every other offset stays valid; a JPEG without EXIF
     * gets a small EXIF block after its start (and after a JFIF block, which has to come first).
     *
     * @throws IOException if the file isn't a JPEG this can safely change
     */
    static byte[] withOrientation(byte[] jpeg, int value) throws IOException {
        if (jpeg.length < 4 || u8(jpeg, 0) != 0xFF || u8(jpeg, 1) != 0xD8) {
            throw new IOException("not a JPEG file");
        }
        int at = 2;
        int insertAt = 2;
        while (true) {
            if (at + 4 > jpeg.length || u8(jpeg, at) != 0xFF) {
                throw new IOException("damaged JPEG file");
            }
            int marker = u8(jpeg, at + 1);
            if (marker == 0xFF) {
                at++;   // fill byte
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) {
                return inserted(jpeg, insertAt, value);   // image data reached without EXIF
            }
            int length = u16(jpeg, at + 2, false);
            int end = at + 2 + length;
            if (length < 2 || end > jpeg.length) {
                throw new IOException("damaged JPEG file");
            }
            if (marker == 0xE1 && length >= 2 + EXIF.length + 8
                    && Arrays.equals(jpeg, at + 4, at + 4 + EXIF.length, EXIF, 0, EXIF.length)) {
                return patched(jpeg, at, end, value);
            }
            if (marker == 0xE0 && at == insertAt) {
                insertAt = end;
            }
            at = end;
        }
    }

    /** The EXIF block runs from {@code segment} (its marker) to {@code end}. */
    private static byte[] patched(byte[] jpeg, int segment, int end, int value) throws IOException {
        int tiff = segment + 4 + EXIF.length;
        boolean little;
        if (jpeg[tiff] == 'I' && jpeg[tiff + 1] == 'I') {
            little = true;
        } else if (jpeg[tiff] == 'M' && jpeg[tiff + 1] == 'M') {
            little = false;
        } else {
            throw new IOException("damaged EXIF data");
        }
        long ifdOffset = u32(jpeg, tiff + 4, little);
        if (u16(jpeg, tiff + 2, little) != 42 || ifdOffset < 8 || tiff + ifdOffset + 2 > end) {
            throw new IOException("damaged EXIF data");
        }
        int ifd = tiff + (int) ifdOffset;
        int count = u16(jpeg, ifd, little);
        int next = ifd + 2 + count * 12;
        if (next + 4 > end) {
            throw new IOException("damaged EXIF data");
        }
        for (int i = 0; i < count; i++) {
            int entry = ifd + 2 + i * 12;
            if (u16(jpeg, entry, little) == ORIENTATION) {
                if (u16(jpeg, entry + 2, little) != SHORT || u32(jpeg, entry + 4, little) != 1) {
                    throw new IOException("unusual EXIF orientation tag");
                }
                byte[] out = jpeg.clone();
                put16(out, entry + 8, value, little);
                return out;
            }
        }
        // No tag: a copy of the IFD with it, at the end of the TIFF data (offsets there must be even).
        int pad = (end - tiff) % 2;
        ByteArrayOutputStream copy = new ByteArrayOutputStream();
        write16(copy, count + 1, little);
        boolean added = false;
        for (int i = 0; i < count; i++) {
            int entry = ifd + 2 + i * 12;
            if (!added && u16(jpeg, entry, little) > ORIENTATION) {
                writeEntry(copy, value, little);
                added = true;
            }
            copy.write(jpeg, entry, 12);
        }
        if (!added) {
            writeEntry(copy, value, little);
        }
        copy.write(jpeg, next, 4);   // the next IFD (the EXIF thumbnail's) stays linked
        int newLength = end - segment - 2 + pad + copy.size();
        if (newLength > 0xFFFF) {
            throw new IOException("the EXIF block is too large to add the orientation");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + pad + copy.size());
        out.write(jpeg, 0, segment + 2);
        write16(out, newLength, false);
        out.write(jpeg, segment + 4, end - segment - 4);
        out.write(new byte[pad], 0, pad);
        out.write(copy.toByteArray(), 0, copy.size());
        out.write(jpeg, end, jpeg.length - end);
        byte[] result = out.toByteArray();
        put32(result, tiff + 4, end - tiff + pad, little);
        return result;
    }

    /** A new EXIF block at {@code at} holding only the orientation. */
    private static byte[] inserted(byte[] jpeg, int at, int value) {
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        block.write(0xFF);
        block.write(0xE1);
        write16(block, 2 + EXIF.length + 8 + 2 + 12 + 4, false);
        block.write(EXIF, 0, EXIF.length);
        block.write('M');
        block.write('M');
        write16(block, 42, false);
        write32(block, 8, false);
        write16(block, 1, false);
        writeEntry(block, value, false);
        write32(block, 0, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length + block.size());
        out.write(jpeg, 0, at);
        out.write(block.toByteArray(), 0, block.size());
        out.write(jpeg, at, jpeg.length - at);
        return out.toByteArray();
    }

    /** The orientation entry: SHORT, count 1, the value in the first two bytes of the value field. */
    private static void writeEntry(ByteArrayOutputStream out, int value, boolean little) {
        write16(out, ORIENTATION, little);
        write16(out, SHORT, little);
        write32(out, 1, little);
        write16(out, value, little);
        write16(out, 0, little);
    }

    private static int u8(byte[] b, int at) {
        return b[at] & 0xFF;
    }

    private static int u16(byte[] b, int at, boolean little) {
        return little ? u8(b, at) | u8(b, at + 1) << 8 : u8(b, at) << 8 | u8(b, at + 1);
    }

    private static long u32(byte[] b, int at, boolean little) {
        long high = u16(b, at, little);
        long low = u16(b, at + 2, little);
        return little ? low << 16 | high : high << 16 | low;
    }

    private static void put16(byte[] b, int at, int value, boolean little) {
        b[at + (little ? 0 : 1)] = (byte) value;
        b[at + (little ? 1 : 0)] = (byte) (value >> 8);
    }

    private static void put32(byte[] b, int at, long value, boolean little) {
        put16(b, at + (little ? 0 : 2), (int) value, little);
        put16(b, at + (little ? 2 : 0), (int) (value >> 16), little);
    }

    private static void write16(ByteArrayOutputStream out, int value, boolean little) {
        byte[] b = new byte[2];
        put16(b, 0, value, little);
        out.write(b, 0, 2);
    }

    private static void write32(ByteArrayOutputStream out, long value, boolean little) {
        byte[] b = new byte[4];
        put32(b, 0, value, little);
        out.write(b, 0, 4);
    }
}
