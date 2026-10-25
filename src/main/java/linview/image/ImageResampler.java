package linview.image;

import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

/**
 * Shrinks an image with Lanczos-3 (R-ZOOM-06, #24): sharper than the graphics card's scaling, which skips pixels
 * when shrinking a lot and turns fine detail grainy. Separable: the rows first, then the columns, each spread over
 * the CPU cores (7.2 MP → 1920×1080 in ≈ 100 ms; TwelveMonkeys' {@code ResampleOp} took 1–2 s, measured 2026-10-24).
 * Pixels are premultiplied ARGB, so transparent edges don't darken. No JavaFX, so it can be tested without a screen.
 */
public final class ImageResampler {

    /**
     * Larger decoded images are left to the graphics card: their buffers would be too large (#20). The same 40 MP as
     * the loader's {@code ImageLoader.SHRINK_ABOVE_PIXELS}, but for its own reason: changing one doesn't change the
     * other.
     */
    public static final double MAX_SOURCE_PIXELS = 40e6;

    private static final int LOBES = 3;

    private ImageResampler() {
    }

    /** For each output pixel along one side: where its source pixels start, and their weights, summing to 1. */
    private record Taps(int[] start, float[][] weights) {

        static Taps of(int from, int to) {
            double scale = (double) to / from;
            double stretch = Math.min(scale, 1);   // shrinking widens the filter, so every source pixel counts
            double support = LOBES / stretch;
            int[] start = new int[to];
            float[][] weights = new float[to][];
            for (int i = 0; i < to; i++) {
                double centre = (i + 0.5) / scale - 0.5;
                int lo = Math.max(0, (int) Math.floor(centre - support) + 1);
                int hi = Math.min(from - 1, (int) Math.floor(centre + support));
                float[] w = new float[hi - lo + 1];
                double sum = 0;
                for (int j = lo; j <= hi; j++) {
                    w[j - lo] = (float) lanczos((j - centre) * stretch);
                    sum += w[j - lo];
                }
                for (int j = 0; j < w.length; j++) {
                    w[j] /= (float) sum;
                }
                start[i] = lo;
                weights[i] = w;
            }
            return new Taps(start, weights);
        }
    }

    static double lanczos(double x) {
        x = Math.abs(x);
        if (x < 1e-9) {
            return 1;
        }
        if (x >= LOBES) {
            return 0;
        }
        double p = Math.PI * x;
        return LOBES * Math.sin(p) * Math.sin(p / LOBES) / (p * p);
    }

    /**
     * {@code pixels} ({@code width} × {@code height}, premultiplied ARGB, row by row) at {@code toWidth} ×
     * {@code toHeight}.
     *
     * @return the new pixels, or {@code null} once {@code cancelled} says so: it is asked after every row
     */
    public static int[] lanczos(int[] pixels, int width, int height, int toWidth, int toHeight,
                                BooleanSupplier cancelled) {
        Taps across = Taps.of(width, toWidth);
        Taps down = Taps.of(height, toHeight);
        float[] rows = new float[toWidth * height * 4];   // the rows done: A, R, G, B per pixel
        boolean stopped = IntStream.range(0, height).parallel().anyMatch(y -> {
            if (cancelled.getAsBoolean()) {
                return true;
            }
            for (int x = 0; x < toWidth; x++) {
                int start = y * width + across.start[x];
                float[] w = across.weights[x];
                float a = 0;
                float r = 0;
                float g = 0;
                float b = 0;
                for (int j = 0; j < w.length; j++) {
                    int p = pixels[start + j];
                    a += (p >>> 24) * w[j];
                    r += (p >> 16 & 0xff) * w[j];
                    g += (p >> 8 & 0xff) * w[j];
                    b += (p & 0xff) * w[j];
                }
                int o = (y * toWidth + x) * 4;
                rows[o] = a;
                rows[o + 1] = r;
                rows[o + 2] = g;
                rows[o + 3] = b;
            }
            return false;
        });
        if (stopped) {
            return null;
        }
        int[] result = new int[toWidth * toHeight];
        stopped = IntStream.range(0, toHeight).parallel().anyMatch(y -> {
            if (cancelled.getAsBoolean()) {
                return true;
            }
            int start = down.start[y];
            float[] w = down.weights[y];
            for (int x = 0; x < toWidth; x++) {
                float a = 0;
                float r = 0;
                float g = 0;
                float b = 0;
                for (int j = 0; j < w.length; j++) {
                    int o = ((start + j) * toWidth + x) * 4;
                    a += rows[o] * w[j];
                    r += rows[o + 1] * w[j];
                    g += rows[o + 2] * w[j];
                    b += rows[o + 3] * w[j];
                }
                // Lanczos overshoots at sharp edges; premultiplied colour can't be above its alpha.
                int alpha = clamp(a, 255);
                result[y * toWidth + x] = alpha << 24 | clamp(r, alpha) << 16 | clamp(g, alpha) << 8 | clamp(b, alpha);
            }
            return false;
        });
        return stopped ? null : result;
    }

    private static int clamp(float value, int max) {
        return value <= 0 ? 0 : Math.min(max, Math.round(value));
    }
}
