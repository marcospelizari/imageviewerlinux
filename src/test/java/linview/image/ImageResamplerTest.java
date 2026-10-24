package linview.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** #24: Lanczos-3 shrinking. */
class ImageResamplerTest {

    private static int[] filled(int width, int height, int argb) {
        int[] pixels = new int[width * height];
        Arrays.fill(pixels, argb);
        return pixels;
    }

    @Test
    void aFlatColourStaysFlat() {
        int colour = 0xff336699;
        int[] out = ImageResampler.lanczos(filled(400, 300, colour), 400, 300, 130, 97, () -> false);

        assertEquals(130 * 97, out.length);
        for (int p : out) {
            assertEquals(colour, p);
        }
    }

    /** One-pixel black and white stripes, shrunk to a quarter: grey, not stripes (what the graphics card shows). */
    @Test
    void fineStripesAverageOut() {
        int[] stripes = new int[400 * 4];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 400; x++) {
                stripes[y * 400 + x] = x % 2 == 0 ? 0xff000000 : 0xffffffff;
            }
        }
        int[] out = ImageResampler.lanczos(stripes, 400, 4, 100, 1, () -> false);

        for (int x = 2; x < 98; x++) {   // away from the edges
            int grey = out[x] & 0xff;
            assertTrue(Math.abs(grey - 128) <= 2, "pixel " + x + " = " + grey);
        }
    }

    /** A sharp edge between transparent and opaque: Lanczos overshoots, but colour never exceeds its alpha. */
    @Test
    void premultipliedColourStaysWithinItsAlpha() {
        int[] edge = new int[60 * 2];
        for (int i = 0; i < edge.length; i++) {
            edge[i] = i % 60 < 30 ? 0x00000000 : 0xffffffff;
        }
        int[] out = ImageResampler.lanczos(edge, 60, 2, 25, 1, () -> false);

        for (int p : out) {
            int alpha = p >>> 24;
            assertTrue((p >> 16 & 0xff) <= alpha && (p >> 8 & 0xff) <= alpha && (p & 0xff) <= alpha,
                    Integer.toHexString(p));
        }
    }

    @Test
    void cancellingGivesNothing() {
        assertNull(ImageResampler.lanczos(filled(200, 200, 0xffffffff), 200, 200, 50, 50, () -> true));
    }

    @Test
    void theKernelIsOneAtZeroAndZeroBeyondThreeLobes() {
        assertEquals(1, ImageResampler.lanczos(0));
        assertEquals(0, ImageResampler.lanczos(1), 1e-12);
        assertEquals(0, ImageResampler.lanczos(3));
        assertEquals(ImageResampler.lanczos(-0.5), ImageResampler.lanczos(0.5));
    }
}
