package linview.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

/** #21: the selected image first, preloads one at a time, the window of kept images, changed files. */
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

    private final List<Decode> decodes = new ArrayList<>();
    /** Each file's state now; missing = "v1", {@code null} = deleted. */
    private final Map<Path, Object> stamps = new HashMap<>();
    private final LoadScheduler<String> scheduler = new LoadScheduler<>((file, minResolution, preload, done) -> {
        Decode decode = new Decode(file, minResolution, preload, done);
        decodes.add(decode);
        return () -> decode.cancelled = true;
    }, file -> stamps.containsKey(file) ? stamps.get(file) : "v1");
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

    @Test
    void aPreloadedImageIsShownAtOnce() {
        select(A);
        scheduler.preload(List.of(B), 0);
        last().finish();
        last().finish();   // B preloaded

        select(B);

        assertEquals(List.of("a.jpg", "b.jpg"), shown);   // before load returned, without a new decode
        assertEquals(2, decodes.size());
    }

    /** A preload never delays the selected image: one running is cancelled, and it starts again afterwards. */
    @Test
    void selectingAnotherImageCancelsTheRunningPreload() {
        select(A);
        scheduler.preload(List.of(B, C), 0);
        last().finish();
        Decode preloadB = last();

        select(D);

        assertTrue(preloadB.cancelled);
        assertEquals(D, last().file);
        assertFalse(last().preload);
        last().finish();
        scheduler.preload(List.of(B, C), 0);
        assertEquals(B, last().file);   // B again, now that D is shown
        assertTrue(last().preload);
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

    @Test
    void imagesNoLongerWantedAreDropped() {
        select(A);
        scheduler.preload(List.of(B, C), 0);
        last().finish();
        last().finish();
        last().finish();
        assertEquals(3, scheduler.keptCount());   // A, B, C

        select(C);
        scheduler.preload(List.of(D), 0);

        assertEquals(1, scheduler.keptCount());   // only C (current); B dropped, D still loading
        assertEquals(D, last().file);
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

    /** The full image for a zoom (#22) goes with the image: it isn't kept for later. */
    @Test
    void moreThanThePreloadsAskForIsNotKept() {
        select(A);
        scheduler.preload(List.of(), 0);
        last().done.accept(new Loaded<>("a copy", 3840, 2160, Orientation.UPRIGHT, 0.5, 0), "v1");
        scheduler.load(A, 1, result -> shown.add(String.valueOf(result.image())));
        assertEquals(1, last().minResolution);
        last().finish();

        select(A);

        assertEquals(List.of("a copy", "a.jpg", "a copy"), shown);   // the copy again, from memory
        assertEquals(2, decodes.size());
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
}
