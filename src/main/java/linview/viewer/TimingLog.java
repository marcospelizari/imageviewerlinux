package linview.viewer;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Timing log (#23), on with {@code --timing}: one line per image, and a summary when the app closes. "Shown" is
 * the agreed measure (REPORT.md §5.2): from the key press reaching the controller until the image is handed to
 * the window; the frame follows about 16 ms later. "Load" is the loader's own time, from starting the load until
 * the image was ready. {@link #OFF} does nothing, so callers never check whether the log is on.
 */
public final class TimingLog {

    public static final TimingLog OFF = new TimingLog(null, System::nanoTime);

    /** The log on standard error. */
    public static TimingLog toStandardError() {
        return new TimingLog(System.err::println, System::nanoTime);
    }

    private final Consumer<String> out;
    private final LongSupplier clock;
    private final List<Double> shown = new ArrayList<>();

    TimingLog(Consumer<String> out, LongSupplier clock) {
        this.out = out;
        this.clock = clock;
    }

    /** Now, in nanoseconds, for {@link #shown} and {@link #failed}. */
    long now() {
        return clock.getAsLong();
    }

    synchronized void shown(String name, long start, double loadMillis) {
        if (out == null) {
            return;
        }
        double millis = millisSince(start);
        shown.add(millis);
        out.accept(String.format(Locale.ROOT, "timing  %s  shown %.1f ms  load %.1f ms", name, millis, loadMillis));
    }

    /** #22: the full image of {@code name} replaced its screen-size copy. */
    void full(String name, double loadMillis) {
        if (out != null) {
            out.accept(String.format(Locale.ROOT, "timing  %s  full resolution, load %.1f ms", name, loadMillis));
        }
    }

    void failed(String name, long start) {
        if (out != null) {
            out.accept(String.format(Locale.ROOT, "timing  %s  failed after %.1f ms", name, millisSince(start)));
        }
    }

    /**
     * When the app closes (a shutdown hook, so also after Ctrl+C): how many images were shown, the median and the time 9 of 10 were shown within (the
     * pass rule), then the peak of each heap pool. The pools peak at different moments, so their sum is an upper
     * bound of the peak heap, not the peak itself; a GC log ({@code -Xlog:gc}) gives the heap at each collection.
     */
    public synchronized void summary() {
        if (out == null) {
            return;
        }
        if (!shown.isEmpty()) {
            List<Double> sorted = new ArrayList<>(shown);
            sorted.sort(null);
            out.accept(String.format(Locale.ROOT, "timing  %d images shown: median %.1f ms, 9 of 10 within %.1f ms",
                    sorted.size(), median(sorted), nineOfTen(sorted)));
        }
        long sum = 0;
        List<String> pools = new ArrayList<>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.getPeakUsage() != null) {
                long peak = pool.getPeakUsage().getUsed();
                sum += peak;
                pools.add(pool.getName() + " " + megabytes(peak) + " MB");
            }
        }
        out.accept("timing  heap peak per pool: " + String.join(", ", pools) + "; sum " + megabytes(sum) + " MB");
    }

    /** The middle value of {@code sorted}; the mean of the two middle ones for an even count. */
    static double median(List<Double> sorted) {
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    /** The smallest time that at least 9 of every 10 values in {@code sorted} are within (nearest rank). */
    static double nineOfTen(List<Double> sorted) {
        int rank = (int) Math.ceil(sorted.size() * 0.9);
        return sorted.get(rank - 1);
    }

    private double millisSince(long start) {
        return (clock.getAsLong() - start) / 1e6;
    }

    private static long megabytes(long bytes) {
        return Math.round(bytes / (1024.0 * 1024));
    }
}
