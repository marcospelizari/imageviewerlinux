package linview.image;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** R-FMT-03: EXIF orientation values 1–8 and the fallbacks. */
class OrientationTest {

    @Test
    void allEightValues() {
        assertEquals(new Orientation(0, false), Orientation.of(1));
        assertEquals(new Orientation(0, true), Orientation.of(2));
        assertEquals(new Orientation(180, false), Orientation.of(3));
        assertEquals(new Orientation(180, true), Orientation.of(4));
        assertEquals(new Orientation(270, true), Orientation.of(5));
        assertEquals(new Orientation(90, false), Orientation.of(6));
        assertEquals(new Orientation(90, true), Orientation.of(7));
        assertEquals(new Orientation(270, false), Orientation.of(8));
    }

    /** #51: what saving a rotation writes is the value read back. */
    @Test
    void exifValueIsTheInverseOfOf() {
        for (int value = 1; value <= 8; value++) {
            assertEquals(value, Orientation.of(value).exifValue());
        }
        assertEquals(6, Orientation.UPRIGHT.rotated(1).exifValue());
        assertEquals(5, Orientation.of(2).rotated(-1).exifValue());   // mirror kept
    }

    @Test
    void unknownValuesAreUpright() {
        assertEquals(Orientation.UPRIGHT, Orientation.of(0));
        assertEquals(Orientation.UPRIGHT, Orientation.of(9));
        assertEquals(Orientation.UPRIGHT, Orientation.of(-1));
    }

    @Test
    void sidewaysValuesSwapWidthAndHeight() {
        for (int value = 1; value <= 8; value++) {
            assertEquals(value >= 5, Orientation.of(value).swapsSides(), "value " + value);
        }
    }

    /** R-VIEW-01: R / L add quarter turns after the EXIF orientation, mirror kept. */
    @Test
    void rotatedAddsQuarterTurns() {
        assertEquals(new Orientation(90, false), Orientation.UPRIGHT.rotated(1));
        assertEquals(new Orientation(270, false), Orientation.UPRIGHT.rotated(-1));
        assertEquals(Orientation.UPRIGHT, Orientation.of(8).rotated(1));
        assertEquals(new Orientation(0, true), Orientation.of(5).rotated(5));
        assertEquals(Orientation.UPRIGHT, Orientation.UPRIGHT.rotated(4));
    }

    /**
     * #28: thumbnails are stored upright. A 3 × 2 image, pixels numbered row by row:
     * <pre>
     * 1 2 3
     * 4 5 6
     * </pre>
     */
    @Test
    void pixelsAreTurnedUprightForEveryExifValue() {
        int[] stored = {1, 2, 3, 4, 5, 6};
        assertArrayEquals(new int[] {1, 2, 3, 4, 5, 6}, Orientation.of(1).turn(stored, 3, 2));
        assertArrayEquals(new int[] {3, 2, 1, 6, 5, 4}, Orientation.of(2).turn(stored, 3, 2));   // mirrored
        assertArrayEquals(new int[] {6, 5, 4, 3, 2, 1}, Orientation.of(3).turn(stored, 3, 2));   // 180
        assertArrayEquals(new int[] {4, 5, 6, 1, 2, 3}, Orientation.of(4).turn(stored, 3, 2));   // flipped
        // Sideways: the result is 2 wide, 3 high.
        assertArrayEquals(new int[] {1, 4, 2, 5, 3, 6}, Orientation.of(5).turn(stored, 3, 2));   // transposed
        assertArrayEquals(new int[] {4, 1, 5, 2, 6, 3}, Orientation.of(6).turn(stored, 3, 2));   // 90 clockwise
        assertArrayEquals(new int[] {6, 3, 5, 2, 4, 1}, Orientation.of(7).turn(stored, 3, 2));   // transverse
        assertArrayEquals(new int[] {3, 6, 2, 5, 1, 4}, Orientation.of(8).turn(stored, 3, 2));   // 270 clockwise
    }

    /** #60: the magnifier's cut-out reads each upright pixel from where {@code turn} took it, for every EXIF value. */
    @Test
    void storedIndexIsTheInverseOfTurn() {
        int width = 3;
        int height = 2;
        int[] stored = {0, 1, 2, 3, 4, 5};
        for (int exif = 1; exif <= 8; exif++) {
            Orientation orientation = Orientation.of(exif);
            int[] upright = orientation.turn(stored, width, height);
            int uprightWidth = orientation.swapsSides() ? height : width;
            for (int i = 0; i < upright.length; i++) {
                int index = orientation.storedIndex(i % uprightWidth, i / uprightWidth, width, height);
                assertEquals(upright[i], stored[index], "EXIF " + exif + ", pixel " + i);
            }
        }
    }

}
