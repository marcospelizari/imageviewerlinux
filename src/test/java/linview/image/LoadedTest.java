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
}
