package linview.image.loading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import linview.image.Loaded;
import linview.image.Orientation;

/** #21: the selected image first, preloads one at a time, changed files; #20: the cache's budget. */
class LoadSchedulerTest {

    /** A decode the test finishes by hand. */
    private static final class Decode {
        final Path file;
        final double minResolution;
        final boolean preload;
        final BiConsumer<Loaded<String>, Object> done;
        boolean cancelled;

        Decode(Path file, double minResolution, boolean preload, BiConsumer<Loaded<String>, Object> done) {
            this.file = file;
            this.minResolution = minResolution;
            this.preload = preload;
            this.done = done;
        }

        void finish(Object stamp) {
            done.accept(new Loaded<>(file.getFileName().toString(), 800, 600, Orientation.UPRIGHT), stamp);
        }

        void finish() {
            finish("v1");
        }

        void fail() {
            done.accept(Loaded.failed(), "v1");
        }
    }

    private static final Path A = Path.of("/pics/a.jpg");
    private static final Path B = Path.of("/pics/b.jpg");
    private static final Path C = Path.of("/pics/c.jpg");
    private static final Path D = Path.of("/pics/d.jpg");
    private static final Path E = Path.of("/pics/e.jpg");

    private final List<Decode> decodes = new ArrayList<>();
    /** Each file's state now; missing = "v1", {@code null} = deleted. */
    private final Map<Path, Object> stamps = new HashMap<>();
    /** When true, freshness checks wait in {@link #slowStamps} until the test answers them, like a slow USB disk. */
    private boolean slowDisk;
    private final List<Runnable> slowStamps = new ArrayList<>();
    /** One test image: 800 × 600 × 4 bytes. */
    private static final long IMAGE = 800 * 600 * 4;
    private final LoadScheduler<String> scheduler = new LoadScheduler<>(new LoadScheduler.Decoder<>() {
        @Override
        public Runnable decode(Path file, double minResolution, boolean preload,
                               BiConsumer<Loaded<String>, Object> done) {
            Decode decode = new Decode(file, minResolution, preload, done);
            decodes.add(decode);
            return () -> decode.cancelled = true;
        }

        @Override
        public Runnable stamp(Path file, Consumer<Object> done) {
            boolean[] cancelled = {false};
            Runnable answer = () -> {
                if (!cancelled[0]) {
                    done.accept(stamps.containsKey(file) ? stamps.get(file) : "v1");
                }
            };
            if (slowDisk) {
                slowStamps.add(answer);
            } else {
                answer.run();
            }
            return () -> cancelled[0] = true;
        }
    }, 4 * IMAGE);
    private final List<String> shown = new ArrayList<>();

    private Runnable select(Path file) {
        Runnable cancel = scheduler.load(file, 0, result -> shown.add(String.valueOf(result.image())));
        return cancel;
    }

    private Decode last() {
        return decodes.get(decodes.size() - 1);
    }

    @Test
    void theSelectedImageLoadsFirstThenPreloadsOneAtATime() {
        select(A);
        scheduler.preload(List.of(B, C, D), 0);
        assertEquals(1, decodes.size());   // nothing preloads while A loads
        assertFalse(last().preload);

        last().finish();
        assertEquals(List.of("a.jpg"), shown);
        assertEquals(B, last().file);
        assertTrue(last().preload);
        assertEquals(2, decodes.size());

        last().finish();
        assertEquals(C, last().file);
        last().finish();
        assertEquals(D, last().file);
        last().finish();
        assertEquals(4, decodes.size());   // A, B, C, D: all wanted images kept, nothing more to do
    }

    /** #69: every answer carries the file's state from when it was loaded, from the cache too. */
    @Test
    void resultsCarryTheStampFromLoading() {
        List<Loaded<String>> results = new ArrayList<>();
        scheduler.load(A, 0, results::add);
        scheduler.preload(List.of(B), 0);
        last().finish("v1");
        last().finish("v1");             // B preloaded
        scheduler.load(B, 0, results::add);
        assertEquals("v1", results.get(0).stamp());
        assertEquals("v1", results.get(1).stamp());
        assertTrue(results.get(1).cached());
    }

    @Test
    void aPreloadedImageIsShownAtOnce() {
        select(A);
        scheduler.preload(List.of(B), 0);
        last().finish();
        last().finish();   // B preloaded

        List<Loaded<String>> results = new ArrayList<>();
        scheduler.load(B, 0, results::add);

        assertEquals("b.jpg", results.get(0).image());   // before load returned, without a new decode
        assertTrue(results.get(0).cached());   // for the timing log
        assertEquals(2, decodes.size());
    }

    /**
     * #55: a running preload still wanted goes on beside the selected image instead of being cancelled, since JavaFX
     * can't stop a decode it has started: cancelling would only decode it twice.
     */
    @Test
    void aRunningPreloadStillWantedGoesOn() {
        select(A);
        scheduler.preload(List.of(B, C), 0);
        last().finish();
        Decode preloadB = last();

        select(D);
        scheduler.preload(List.of(B, C), 0);

        assertFalse(preloadB.cancelled);
        assertEquals(D, last().file);   // the selected image starts at once, beside the preload
        assertFalse(last().preload);
        last().finish();
        assertEquals(3, decodes.size());   // no second preload while B still runs
        preloadB.finish();
        assertEquals(C, last().file);
        assertEquals(1, decodes.stream().filter(d -> d.file.equals(B)).count());   // B decoded once
    }

    /** A running preload whose image is no longer wanted is cancelled. */
    @Test
    void aRunningPreloadNoLongerWantedIsCancelled() {
        select(A);
        scheduler.preload(List.of(B), 0);
        last().finish();
        Decode preloadB = last();

        select(D);
        scheduler.preload(List.of(C), 0);

        assertTrue(preloadB.cancelled);
        last().finish();   // D
        assertEquals(C, last().file);
    }

    /** The selected image is the one being preloaded: it waits for that load instead of decoding it twice. */
    @Test
    void theSelectedImageTakesOverItsPreload() {
        select(A);
        scheduler.preload(List.of(B, C), 0);
        last().finish();
        Decode preloadB = last();

        select(B);
        scheduler.preload(List.of(C), 0);

        assertFalse(preloadB.cancelled);
        assertEquals(2, decodes.size());
        preloadB.finish();
        assertEquals(List.of("a.jpg", "b.jpg"), shown);
        assertEquals(C, last().file);   // preloading goes on
    }

    /** Holding →: each selection cancels the one before; only the last one shows, then preloading starts. */
    @Test
    void fastSelectionShowsOnlyTheLastImage() {
        Runnable cancel = select(A);
        scheduler.preload(List.of(B, C), 0);
        for (Path file : List.of(B, C, D)) {
            Runnable next = select(file);
            cancel.run();
            scheduler.preload(List.of(), 0);
            cancel = next;
        }
        decodes.get(0).finish();   // too late: cancelled
        decodes.get(1).finish();
        decodes.get(2).finish();

        last().finish();

        assertEquals(List.of("d.jpg"), shown);
        assertTrue(decodes.subList(0, 3).stream().allMatch(d -> d.cancelled));
    }

    /** An older selected load cancelled late must not unblock preloading while the newer one still loads. */
    @Test
    void cancellingAnOlderLoadKeepsPreloadsWaiting() {
        Runnable cancelA = select(A);
        select(B);
        cancelA.run();
        scheduler.preload(List.of(C), 0);

        assertEquals(2, decodes.size());   // B still loading: no preload yet
        last().finish();
        assertEquals(C, last().file);
    }

    /** #20: images no longer wanted stay while the budget has room, so going back is instant. */
    @Test
    void imagesNoLongerWantedStayWhileTheyFit() {
        select(A);
        scheduler.preload(List.of(B, C), 0);
        last().finish();
        last().finish();
        last().finish();

        select(C);
        scheduler.preload(List.of(D), 0);

        assertEquals(3, scheduler.keptCount());   // A, B, C; D still loading
        assertEquals(D, last().file);
        select(A);
        assertEquals(List.of("a.jpg", "c.jpg", "a.jpg"), shown);   // A again without a decode
        assertEquals(4, decodes.size());
    }

    /** #20: over the budget (4 images here) the least recently used image goes first, never one still wanted. */
    @Test
    void theLeastRecentlyUsedImageIsDroppedFirst() {
        select(A);
        scheduler.preload(List.of(B, C), 0);
        last().finish();
        last().finish();
        last().finish();   // A, B, C
        select(B);         // B used again: A is now the least recently used
        scheduler.preload(List.of(C, D), 0);
        last().finish();   // D: A, B, C, D = the budget
        select(C);
        scheduler.preload(List.of(D, E), 0);
        last().finish();   // E: one too many

        assertEquals(4, scheduler.keptCount());
        assertEquals(4 * IMAGE, scheduler.keptBytes());
        int before = decodes.size();
        select(B);
        assertEquals(before, decodes.size());   // B kept
        select(A);
        assertEquals(before + 1, decodes.size());   // A dropped
    }

    /** #20: preloading pauses while the current and wanted images fill the budget. */
    @Test
    void preloadingPausesWhenTheBudgetIsFull() {
        select(A);
        scheduler.preload(List.of(B, C, D, E), 0);
        last().finish();
        last().finish();
        last().finish();
        last().finish();   // A, B, C, D = the budget

        assertEquals(4, decodes.size());   // E isn't started
        assertEquals(D, last().file);
    }

    /** #20: when wanted images alone are over the budget, the least wanted goes, never the current image. */
    @Test
    void theLeastWantedImageGoesWhenWantedOnesDoNotFit() {
        select(A);
        scheduler.preload(List.of(B, C, D), 0);
        last().finish();
        last().finish();
        last().finish();
        last().done.accept(new Loaded<>("d.jpg", 1600, 600, Orientation.UPRIGHT), "v1");   // two images' worth

        assertEquals(3, scheduler.keptCount());   // D dropped again
        int before = decodes.size();
        select(A);
        select(B);
        select(C);
        assertEquals(before, decodes.size());
        scheduler.preload(List.of(B, C, D), 0);
        assertEquals(before, decodes.size());   // D not tried again while wanted (#57)
    }

    /** #20: the current image stays even when it alone is over the budget. */
    @Test
    void theCurrentImageStaysWhateverItsSize() {
        select(A);
        scheduler.preload(List.of(), 0);
        last().done.accept(new Loaded<>("a big", 2000, 2000, Orientation.UPRIGHT), "v1");   // 16 MB > 4 images

        assertEquals(1, scheduler.keptCount());
        select(A);
        assertEquals(List.of("a big", "a big"), shown);
    }

    /** #20: an image too large to preload isn't kept, but loads normally once selected. */
    @Test
    void anImageTooLargeToPreloadLoadsWhenSelected() {
        select(A);
        scheduler.preload(List.of(B), 0);
        last().finish();
        last().done.accept(Loaded.tooLarge(17869, 12802), "v1");

        assertEquals(1, scheduler.keptCount());
        select(B);
        assertEquals(B, last().file);
        assertFalse(last().preload);
    }

    /** #20: a selection waiting for its preload decodes it itself when the preload was too large. */
    @Test
    void aTakenOverPreloadTooLargeIsDecodedAsSelected() {
        select(A);
        scheduler.preload(List.of(B), 0);
        last().finish();
        Decode preloadB = last();
        select(B);
        assertEquals(2, decodes.size());   // waits for the preload

        preloadB.done.accept(Loaded.tooLarge(17869, 12802), "v1");

        assertEquals(3, decodes.size());
        assertFalse(last().preload);
        assertEquals(List.of("a.jpg"), shown);
    }

    /** #20: what an image takes in the cache: its decoded pixels, 4 bytes each; a failure nothing. */
    @Test
    void decodedBytesCountTheCopyNotTheFile() {
        assertEquals(2000L * 1000 * 4,
                LoadScheduler.decodedBytes(new Loaded<>("copy", 20000, 10000, Orientation.UPRIGHT, 0.1, 0)));
        assertEquals(0, LoadScheduler.decodedBytes(Loaded.failed()));
    }

    /** R4: a file changed or deleted since it was preloaded is loaded again, never shown from memory. */
    @Test
    void aChangedOrDeletedFileIsLoadedAgain() {
        select(A);
        scheduler.preload(List.of(B, C), 0);
        last().finish();
        last().finish();
        last().finish();   // B and C kept as "v1"
        stamps.put(B, "v2");
        stamps.put(C, null);

        select(B);
        assertEquals(B, last().file);
        assertFalse(last().preload);
        last().finish("v2");
        select(C);
        assertEquals(C, last().file);

        assertEquals(List.of("a.jpg", "b.jpg"), shown);
    }

    /** A damaged file fails once: it isn't preloaded again, and selecting it gives the failure at once. */
    @Test
    void aFailedPreloadIsKeptAndNotRetried() {
        select(A);
        scheduler.preload(List.of(B, C), 0);
        last().finish();
        last().fail();
        assertEquals(C, last().file);

        List<Loaded<String>> results = new ArrayList<>();
        scheduler.load(B, 0, results::add);

        assertTrue(results.get(0).isFailed());
    }

    /** The full image of a huge one for a zoom (#22) goes with the image: it isn't kept for later (#55). */
    @Test
    void aHugeFullImageIsNotKept() {
        select(A);
        scheduler.preload(List.of(), 0);
        last().done.accept(new Loaded<>("a copy", 20000, 10000, Orientation.UPRIGHT, 0.1, 0), "v1");
        scheduler.load(A, 1, result -> shown.add(String.valueOf(result.image())));
        assertEquals(1, last().minResolution);
        last().done.accept(new Loaded<>("a full", 20000, 10000, Orientation.UPRIGHT, 1, 0), "v1");

        select(A);

        assertEquals(List.of("a copy", "a full", "a copy"), shown);   // the copy again, from memory
        assertEquals(2, decodes.size());
    }

    /** #55: preloads ask for what the zoom mode needs; the decoder caps huge ones at 40 MP. */
    @Test
    void preloadsAskForWhatTheZoomModeNeeds() {
        scheduler.load(A, 0.25, result -> { });
        scheduler.preload(List.of(B), 0.25);
        last().finish();

        assertEquals(B, last().file);
        assertTrue(last().preload);
        assertEquals(0.25, last().minResolution);
    }

    /** #55: the selected image takes its preload over; a screen copy too small for the zoom is then decoded again. */
    @Test
    void aTakenOverPreloadWithTooLittleResolutionIsDecodedAgain() {
        select(A);
        scheduler.preload(List.of(B), 0);
        last().finish();
        Decode preloadB = last();

        scheduler.load(B, 1, result -> shown.add(String.valueOf(result.image())));
        scheduler.preload(List.of(C), 0);
        assertEquals(2, decodes.size());   // waiting for the preload
        preloadB.done.accept(new Loaded<>("b copy", 20000, 10000, Orientation.UPRIGHT, 0.1, 0), "v1");

        assertEquals(B, last().file);
        assertEquals(1, last().minResolution);
        assertFalse(last().preload);
        assertEquals(List.of("a.jpg"), shown);
        last().finish();
        assertEquals(List.of("a.jpg", "b.jpg"), shown);
        assertEquals(C, last().file);   // preloading goes on afterwards
    }

    /** #55: the freshness check runs on the file system, which can be slow; a late answer for an old pick is unused. */
    @Test
    void aSlowFreshnessCheckDoesNotShowAnImageNoLongerSelected() {
        select(A);
        scheduler.preload(List.of(B, C), 0);
        last().finish();
        last().finish();
        last().finish();   // B and C kept
        slowDisk = true;

        select(B);
        assertEquals(List.of("a.jpg"), shown);   // not shown before the check answers
        select(C);
        slowStamps.get(0).run();   // B's answer arrives late: ignored
        assertEquals(List.of("a.jpg"), shown);
        slowStamps.get(1).run();

        assertEquals(List.of("a.jpg", "c.jpg"), shown);
        assertEquals(3, decodes.size());   // both from memory, nothing decoded
    }

    /** While the freshness check runs, the selected image counts as loading: no preload starts. */
    @Test
    void noPreloadStartsWhileTheFreshnessCheckRuns() {
        select(A);
        scheduler.preload(List.of(B), 0);
        last().finish();
        last().finish();   // B kept
        slowDisk = true;

        select(B);
        scheduler.preload(List.of(C), 0);
        assertEquals(2, decodes.size());

        slowStamps.get(0).run();
        assertEquals(C, last().file);
    }

    /** A kept image with less resolution than asked for (e.g. after switching to actual size) is loaded again. */
    @Test
    void aKeptImageWithTooLittleResolutionIsLoadedAgain() {
        select(A);
        scheduler.preload(List.of(B), 0);
        last().finish();
        last().done.accept(new Loaded<>("b copy", 3840, 2160, Orientation.UPRIGHT, 0.5, 0), "v1");

        scheduler.load(B, 1, result -> shown.add(String.valueOf(result.image())));

        assertEquals(B, last().file);
        assertEquals(1, last().minResolution);
        assertEquals(List.of("a.jpg"), shown);   // B waits for its full image
    }

    /**
     * #57: a preload whose result is too large to keep (rounding past the 40 MP cap) is not started again while its
     * image stays wanted; the next wanted image goes on.
     */
    @Test
    void aPreloadTooLargeToKeepIsNotRetried() {
        select(A);
        scheduler.preload(List.of(B, C), 1);
        last().finish();
        Decode preloadB = last();

        preloadB.done.accept(new Loaded<>("b", 20000, 10000, Orientation.UPRIGHT, 0.5, 0), "v1");   // 50 MP

        assertEquals(C, last().file);   // not B again
        last().finish();
        scheduler.preload(List.of(B, C), 1);   // the next navigation, B still wanted
        assertEquals(1, decodes.stream().filter(d -> d.file.equals(B)).count());
    }

    /** Once the image has left the window, it may be tried again when it comes back. */
    @Test
    void aRejectedPreloadIsTriedAgainAfterLeavingTheWindow() {
        select(A);
        scheduler.preload(List.of(B), 1);
        last().finish();
        last().done.accept(new Loaded<>("b", 20000, 10000, Orientation.UPRIGHT, 0.5, 0), "v1");

        scheduler.preload(List.of(C), 1);
        last().finish();
        scheduler.preload(List.of(B), 1);

        assertEquals(B, last().file);
        assertEquals(2, decodes.stream().filter(d -> d.file.equals(B)).count());
    }
}
