package linview.image;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Which image loads when (#21): the selected image first, then the preloads one at a time, and the window of kept
 * images: the current image plus the wanted ones, anything else dropped. The selected image never waits for a
 * preload: none starts while it loads, and one already running goes on beside it rather than in front of it; when
 * the selected image is the one being preloaded it takes that load over. A running preload is cancelled only when
 * its image is no longer wanted: JavaFX can't stop a decode it has started, so cancelling one still wanted would
 * only waste it and decode the image again later (#55). A kept image is used only while its file is
 * unchanged (R4), checked off the JavaFX thread (#55). Preloads ask for what the zoom mode needs, which the decoder
 * caps at {@link ImageLoader#SHRINK_ABOVE_PIXELS} decoded pixels, and nothing larger is kept, so the window stays
 * bounded (#55). No JavaFX and no threads of its own: {@link ImageLoader} supplies the decoding, so this can be
 * tested without a screen. All calls happen on the JavaFX thread, and so do the decoder's results.
 *
 * @param <I> the image type: JavaFX {@code Image} in the app
 */
final class LoadScheduler<I> {

    /** The work done on other threads; results come back on the JavaFX thread, never after a cancel. */
    interface Decoder<I> {
        /** Decodes one file; {@code done} gets the result and the file's state when loading began. */
        Runnable decode(Path file, double minResolution, boolean preload, BiConsumer<Loaded<I>, Object> done);

        /** The file's state now (e.g. modified time and size), {@code null} when it can't be read. */
        Runnable stamp(Path file, Consumer<Object> done);
    }

    /** A loaded image with the state of its file when loading began. */
    private record Kept<I>(Loaded<I> image, Object stamp) {
    }

    /** One selection: answered once, unless cancelled, whichever way it is being served. */
    private final class Request {
        final Path file;
        final double minResolution;
        final Consumer<Loaded<I>> done;
        boolean cancelled;
        Runnable cancelWork = () -> { };

        Request(Path file, double minResolution, Consumer<Loaded<I>> done) {
            this.file = file;
            this.minResolution = minResolution;
            this.done = done;
        }

        void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            cancelWork.run();
            if (takeOver == this) {
                takeOver = null;
            }
            if (selected == this) {
                selected = null;
            }
        }
    }

    private final Decoder<I> decoder;

    private final Map<Path, Kept<I>> kept = new HashMap<>();
    /** Wanted images whose preload came back too large to keep: not tried again until they leave the window (#57). */
    private final Set<Path> rejected = new HashSet<>();
    private Path current;
    /** The selection still being served, {@code null} once answered; preloads wait while there is one. */
    private Request selected;
    private List<Path> wanted = List.of();
    /** What the zoom mode needs; the decoder decodes a preload no larger than 40 MP whatever this asks (#55). */
    private double wantedResolution;
    /** The one preload running, or {@code null}. */
    private Path preloading;
    private Runnable cancelPreload = () -> { };
    /** The selection waiting for the preload of the same image. */
    private Request takeOver;

    LoadScheduler(Decoder<I> decoder) {
        this.decoder = decoder;
    }

    /** See {@link ImageLoader#load}; a load still being served for an earlier selection is cancelled. */
    Runnable load(Path file, double minResolution, Consumer<Loaded<I>> done) {
        if (selected != null) {
            selected.cancel();   // one selected image at a time: a late answer for an earlier one is never given
        }
        current = file;
        takeOver = null;
        Request request = new Request(file, minResolution, done);
        selected = request;
        Kept<I> ready = kept.get(file);
        if (ready != null && ready.image().resolution() >= minResolution) {
            // Kept, but only while the file is unchanged; asking the file system may be slow (USB, network).
            request.cancelWork = decoder.stamp(file, state -> {
                if (request.cancelled) {
                    return;
                }
                if (state != null && state.equals(ready.stamp()) && kept.get(file) == ready) {
                    answer(request, ready.image().timed(0));
                } else {
                    kept.remove(file);
                    decodeSelected(request);
                }
            });
        } else if (file.equals(preloading)) {
            takeOver = request;   // already being loaded: wait for it rather than decode it twice
        } else {
            decodeSelected(request);
        }
        return request::cancel;
    }

    /**
     * The images likely to come next, most wanted first, each with {@code minResolution} as far as the decoder's cap
     * allows. Kept images no longer wanted are dropped; the others are loaded one at a time once the selected image is
     * ready. An empty list stops preloading.
     * Called after each {@link #load}, which is what starts preloading.
     */
    void preload(List<Path> files, double minResolution) {
        wanted = List.copyOf(files);
        wantedResolution = minResolution;
        kept.keySet().removeIf(file -> !file.equals(current) && !wanted.contains(file));
        rejected.retainAll(wanted);
        if (preloading != null && !wanted.contains(preloading) && takeOver == null) {
            stopPreload();
        }
        preloadNext();
    }

    /** How many images are kept, for tests. */
    int keptCount() {
        return kept.size();
    }

    private void decodeSelected(Request request) {
        request.cancelWork = decoder.decode(request.file, request.minResolution, false, (result, state) -> {
            if (request.cancelled) {
                return;
            }
            keep(request.file, result, state);
            answer(request, result);
        });
    }

    private void answer(Request request, Loaded<I> result) {
        if (selected == request) {
            selected = null;
        }
        request.done.accept(result);
        preloadNext();
    }

    /**
     * Keeps a result while its image is current or wanted, a failure too, so it isn't retried; but nothing decoded
     * larger than {@link ImageLoader#SHRINK_ABOVE_PIXELS}, e.g. the full image of a huge one for a zoom (#22, #55).
     *
     * @return whether it was kept
     */
    private boolean keep(Path file, Loaded<I> result, Object state) {
        double decoded = result.width() * result.height() * result.resolution() * result.resolution();
        if ((file.equals(current) || wanted.contains(file)) && decoded <= ImageLoader.SHRINK_ABOVE_PIXELS) {
            kept.put(file, new Kept<>(result, state));
            return true;
        }
        return false;
    }

    /** Starts the most wanted image not kept yet, unless the selected image or another preload is loading. */
    private void preloadNext() {
        if (selected != null || preloading != null) {
            return;
        }
        for (Path file : wanted) {
            if (!kept.containsKey(file) && !rejected.contains(file)) {
                startPreload(file);
                return;
            }
        }
    }

    private void startPreload(Path file) {
        preloading = file;
        boolean[] cancelled = {false};
        boolean[] finished = {false};
        Runnable cancel = decoder.decode(file, wantedResolution, true, (result, state) -> {
            if (cancelled[0]) {
                return;
            }
            finished[0] = true;
            preloading = null;
            cancelPreload = () -> { };
            if (!keep(file, result, state) && wanted.contains(file)) {
                rejected.add(file);   // else it would be decoded again and again
            }
            Request waiting = takeOver;
            takeOver = null;
            if (waiting != null && !waiting.cancelled && file.equals(current)) {
                if (result.isFailed() || result.resolution() >= waiting.minResolution) {
                    answer(waiting, result);
                } else {
                    decodeSelected(waiting);   // the screen copy isn't enough for this zoom
                }
                return;
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
}
