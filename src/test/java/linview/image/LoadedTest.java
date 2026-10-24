package linview.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A loaded image always has a real size; a failure has no image. */
class LoadedTest {

    @Test
    void aLoadedImageNeedsAPositiveSize() {
        assertThrows(IllegalArgumentException.class, () -> new Loaded<>("img", 0, 10, Orientation.UPRIGHT));
        assertThrows(IllegalArgumentException.class, () -> new Loaded<>("img", 10, -1, Orientation.UPRIGHT));
        assertThrows(IllegalArgumentException.class, () -> new Loaded<>("img", Double.NaN, 10, Orientation.UPRIGHT));
        assertThrows(IllegalArgumentException.class, () -> new Loaded<>("img", 10, 10, null));
    }

    @Test
    void failedHasNoImage() {
        assertTrue(Loaded.failed().isFailed());
    }

    @Test
    void uprightSizeFollowsTheOrientation() {
        Loaded<String> sideways = new Loaded<>("img", 1800, 1200, Orientation.of(6));
        assertEquals(1200, sideways.uprightWidth());
        assertEquals(1800, sideways.uprightHeight());
    }

    /** #23: the load time travels with the image, also when it's turned; a failed result stays failed. */
    @Test
    void timedKeepsTheImageAndRotationKeepsTheTime() {
        Loaded<String> timed = new Loaded<>("img", 800, 600, Orientation.UPRIGHT).timed(42);
        assertEquals(42, timed.loadMillis());
        assertEquals(42, timed.rotated(1).loadMillis());
        assertTrue(Loaded.<String>failed().timed(42).isFailed());
    }

    /** #19: a screen-size copy has a resolution above 0 and up to 1 (full). */
    @Test
    void resolutionMustBeAboveZeroAndAtMostOne() {
        assertThrows(IllegalArgumentException.class, () -> new Loaded<>("img", 10, 10, Orientation.UPRIGHT, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Loaded<>("img", 10, 10, Orientation.UPRIGHT, 1.5, 0));
        assertEquals(1, new Loaded<>("img", 10, 10, Orientation.UPRIGHT).resolution());
    }

    /** #22: the full image takes the copy's place and keeps its orientation, e.g. a rotation. */
    @Test
    void withImageKeepsSizeAndOrientation() {
        Loaded<String> copy = new Loaded<>("copy", 6000, 4000, Orientation.of(6), 0.25, 80).rotated(1);
        Loaded<String> full = new Loaded<>("full", 6000, 4000, Orientation.UPRIGHT, 1, 400);

        Loaded<String> swapped = copy.withImage(full);

        assertEquals("full", swapped.image());
        assertEquals(1, swapped.resolution());
        assertEquals(copy.orientation(), swapped.orientation());
        assertEquals(6000, swapped.width());
        assertEquals(80, swapped.loadMillis());
    }
}
