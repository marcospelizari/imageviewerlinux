package linview.image;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Which image loads when (#21): the selected image first, then the preloads one at a time, and the window of kept
 * images: the current image plus the wanted ones, anything else dropped. The selected image never waits for a
 * preload: none starts while it loads, one running is cancelled when another image is selected, and when the
 * selected image is the one being preloaded it takes that load over. A kept image is used only while its file is
 * unchanged (R4). No JavaFX and no threads of its own: {@link ImageLoader} supplies the decoding, so this can be
 * tested without a screen. All calls happen on the JavaFX thread, and so do the decoder's results.
 *
 * @param <I> the image type: JavaFX {@code Image} in the app
 */
final class LoadScheduler<I> {

    /** Decodes one file; {@code done} gets the result and the file's state when loading began, unless cancelled. */
    @FunctionalInterface
    interface Decoder<I> {
        Runnable decode(Path file, double minResolution, boolean preload, BiConsumer<Loaded<I>, Object> done);
    }

    /** A loaded image with the state of its file when loading began, and the resolution it was asked for. */
    private record Kept<I>(Loaded<I> image, Object stamp, double minResolution) {
    }

    private final Decoder<I> decoder;
    /** A file's state now (e.g. modified time and size); {@code null} when it can't be read. */
    private final Function<Path, Object> stamp;

    private final Map<Path, Kept<I>> kept = new HashMap<>();
    private Path current;
    /** Identifies the selected image's load while it runs, {@code null} otherwise; an older load can't clear it. */
    private Object currentLoad;
    private List<Path> wanted = List.of();
    private double wantedResolution;
    /** The one preload running, or {@code null}. */
    private Path preloading;
    private double preloadingResolution;
    private Runnable cancelPreload = () -> { };
    /** The selected image's result, when it is the one being preloaded: it takes that load over. */
    private Consumer<Loaded<I>> takeOver;

    LoadScheduler(Decoder<I> decoder, Function<Path, Object> stamp) {
        this.decoder = decoder;
        this.stamp = stamp;
    }

    /** See {@link ImageLoader#load}; {@code done} is called before this returns for a kept image. */
    Runnable load(Path file, double minResolution, Consumer<Loaded<I>> done) {
        current = file;
        takeOver = null;
        Kept<I> ready = kept.get(file);
        if (ready != null && !fresh(file, ready)) {
            kept.remove(file);
        } else if (ready != null && ready.image().resolution() >= minResolution) {
            done.accept(ready.image().timed(0));
            return () -> { };
        }
        if (file.equals(preloading) && preloadingResolution >= minResolution) {
            takeOver = done;   // already being loaded: wait for it rather than decode it twice
            return () -> {
                if (takeOver == done) {
                    takeOver = null;
                }
            };
        }
        stopPreload();   // the selected image goes first
        Object token = new Object();
        currentLoad = token;
        boolean[] cancelled = {false};
        // More than the preloads ask for (the full image for a zoom, #22) is not kept: it goes with this image.
        boolean keep = minResolution <= wantedResolution;
        Runnable cancel = decoder.decode(file, minResolution, false, (result, state) -> {
            if (cancelled[0]) {
                return;
            }
            if (currentLoad == token) {
                currentLoad = null;
            }
            if (keep && file.equals(current)) {
                kept.put(file, new Kept<>(result, state, minResolution));
            }
            done.accept(result);
            preloadNext();
        });
        return () -> {
            if (!cancelled[0]) {
                cancelled[0] = true;
                if (currentLoad == token) {
                    currentLoad = null;
                }
                cancel.run();
            }
        };
    }

    /**
     * The images likely to come next, most wanted first, each with at least {@code minResolution}. Kept images no
     * longer wanted are dropped; the others are loaded one at a time once the selected image is ready. An empty list
     * stops preloading. Called after each {@link #load}, which is what starts preloading.
     */
    void preload(List<Path> files, double minResolution) {
        wanted = List.copyOf(files);
        wantedResolution = minResolution;
        kept.keySet().removeIf(file -> !file.equals(current) && !wanted.contains(file));
        if (preloading != null && !wanted.contains(preloading) && takeOver == null) {
            stopPreload();
        }
        preloadNext();
    }

    /** How many images are kept, for tests. */
    int keptCount() {
        return kept.size();
    }

    /** Starts the most wanted image not kept yet, unless the selected image or another preload is loading. */
    private void preloadNext() {
        if (currentLoad != null || preloading != null) {
            return;
        }
        for (Path file : wanted) {
            Kept<I> ready = kept.get(file);
            if (ready == null || ready.minResolution() < wantedResolution) {
                startPreload(file, wantedResolution);
                return;
            }
        }
    }

    private void startPreload(Path file, double minResolution) {
        preloading = file;
        preloadingResolution = minResolution;
        boolean[] cancelled = {false};
        boolean[] finished = {false};
        Runnable cancel = decoder.decode(file, minResolution, true, (result, state) -> {
            if (cancelled[0]) {
                return;
            }
            finished[0] = true;
            preloading = null;
            cancelPreload = () -> { };
            if (wanted.contains(file) || file.equals(current)) {
                kept.put(file, new Kept<>(result, state, minResolution));   // a failure too, so it isn't retried
            }
            Consumer<Loaded<I>> waiting = takeOver;
            takeOver = null;
            if (waiting != null && file.equals(current)) {
                waiting.accept(result);
            }
            preloadNext();
        });
        if (!finished[0]) {   // a decoder may finish before returning; the next preload has its own cancel then
            cancelPreload = () -> {
                cancelled[0] = true;
                cancel.run();
            };
        }
    }

    private void stopPreload() {
        cancelPreload.run();
        cancelPreload = () -> { };
        preloading = null;
    }

    /** The file is unchanged since {@code ready} was loaded; otherwise it is loaded again (R4). */
    private boolean fresh(Path file, Kept<I> ready) {
        return ready.stamp() != null && ready.stamp().equals(stamp.apply(file));
    }
}
