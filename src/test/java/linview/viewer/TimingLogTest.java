package linview.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class TimingLogTest {

    @Test
    void medianOfOddAndEvenCounts() {
        assertEquals(20, TimingLog.median(List.of(10.0, 20.0, 90.0)));
        assertEquals(15, TimingLog.median(List.of(10.0, 20.0)));
    }

    /** The pass rule (REPORT.md §5.2): 9 of 10 navigations under the target. */
    @Test
    void nineOfTenIsTheNearestRank() {
        List<Double> ten = List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 100.0);
        assertEquals(9, TimingLog.nineOfTen(ten));
        assertEquals(5, TimingLog.nineOfTen(List.of(5.0)));
        assertEquals(20, TimingLog.nineOfTen(List.of(10.0, 20.0)));
    }

    @Test
    void summaryCountsShownImagesAndHeapPeaks() {
        List<String> lines = new ArrayList<>();
        long[] now = {0};
        TimingLog log = new TimingLog(lines::add, () -> now[0]);
        for (long millis : new long[] {30, 10, 20}) {
            long start = now[0];
            now[0] += millis * 1_000_000;
            log.shown("img.jpg", start, 0);
        }
        lines.clear();

        log.summary();

        assertEquals("timing  3 images shown: median 20.0 ms, 9 of 10 within 30.0 ms", lines.get(0));
        assertTrue(lines.get(1).startsWith("timing  heap peak per pool: "), lines.get(1));
        assertTrue(lines.get(1).endsWith(" MB"), lines.get(1));
    }

    @Test
    void offLogsNothing() {
        TimingLog.OFF.shown("img.jpg", TimingLog.OFF.now(), 1);
        TimingLog.OFF.failed("img.jpg", TimingLog.OFF.now());
        TimingLog.OFF.summary();   // no output stream, no exception
    }
}
