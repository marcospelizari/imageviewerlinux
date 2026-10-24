package linview.image;

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
}
