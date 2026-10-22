package linview.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * R-ZOOM-01, R-ZOOM-03 on a 1920×1080 view. Sizes are those of the manual test images in
 * ~/Pictures/linview-test: small img1.bmp, tall exif/Portrait_*.jpg, wide exif/47C1….png.
 */
class ZoomTest {

    private static final double VIEW_W = 1920;
    private static final double VIEW_H = 1080;
    private static final double EPS = 1e-9;

    private static final double[] SMALL = {400, 300};
    private static final double[] TALL = {1200, 1800};
    private static final double[] WIDE = {2436, 1125};

    private static double scale(Zoom.Mode mode, double[] image) {
        return scale(mode, 100, image);
    }

    private static double scale(Zoom.Mode mode, double percent, double[] image) {
        return Zoom.scale(mode, percent, image[0], image[1], VIEW_W, VIEW_H);
    }

    private static Zoom.View start(double scale, double[] image) {
        return Zoom.start(scale, image[0], image[1], VIEW_W, VIEW_H);
    }

    @Test
    void fitShowsTheWholeImage() {
        assertEquals(1080.0 / 1800, scale(Zoom.Mode.FIT, TALL), EPS);   // height decides
        assertEquals(1920.0 / 2436, scale(Zoom.Mode.FIT, WIDE), EPS);   // width decides
    }

    @Test
    void fitWidthAndFitHeight() {
        assertEquals(1920.0 / 2436, scale(Zoom.Mode.WIDTH, WIDE), EPS);
        assertEquals(1080.0 / 1125, scale(Zoom.Mode.HEIGHT, WIDE), EPS);
        assertEquals(1080.0 / 1800, scale(Zoom.Mode.HEIGHT, TALL), EPS);
        // Fit width would enlarge the 1200 px wide image; fit modes only shrink.
        assertEquals(1, scale(Zoom.Mode.WIDTH, TALL), EPS);
    }

    @Test
    void actualSizeAndLockIgnoreTheView() {
        assertEquals(1, scale(Zoom.Mode.ACTUAL, WIDE), EPS);
        assertEquals(1, scale(Zoom.Mode.ACTUAL, SMALL), EPS);
        assertEquals(0.7, scale(Zoom.Mode.LOCK, 70, TALL), EPS);
        assertEquals(2, scale(Zoom.Mode.LOCK, 200, SMALL), EPS);   // lock may enlarge: the user chose it
    }

    /** Fit only shrinks: an image smaller than the screen keeps its real size (Marcos, 2026-10-22). */
    @Test
    void smallImagesKeepTheirSize() {
        for (Zoom.Mode mode : new Zoom.Mode[] {Zoom.Mode.FIT, Zoom.Mode.WIDTH, Zoom.Mode.HEIGHT}) {
            assertEquals(1, scale(mode, SMALL), EPS, mode.toString());
        }
    }

    @Test
    void imagesThatFitAreCentred() {
        assertEquals(new Zoom.View(1, 760, 390), start(1, SMALL));
        Zoom.View tall = start(scale(Zoom.Mode.FIT, TALL), TALL);   // 720×1080
        assertEquals(600, tall.x(), EPS);
        assertEquals(0, tall.y(), EPS);
    }

    @Test
    void tallerThanTheViewStartsAtTheTop() {
        Zoom.View view = start(0.7, TALL);   // 840×1260: zoom lock at 70% is still taller than the screen
        assertEquals(540, view.x(), EPS);
        assertEquals(0, view.y(), EPS);
    }

    @Test
    void largerThanTheViewStartsAtTheTopCentre() {
        Zoom.View view = start(1, WIDE);   // actual size, 2436×1125
        assertEquals((1920 - 2436) / 2.0, view.x(), EPS);   // negative: scrolled to the middle
        assertEquals(0, view.y(), EPS);
    }

    @Test
    void imagesThatFitVerticallyAreCentredVertically() {
        Zoom.View view = start(scale(Zoom.Mode.HEIGHT, WIDE), WIDE);   // fit height: 2338×1080
        assertEquals((1920 - 2436 * 1080.0 / 1125) / 2, view.x(), EPS);
        assertEquals(0, view.y(), EPS);
        Zoom.View shorter = start(scale(Zoom.Mode.FIT, WIDE), WIDE);    // 1920×887: centred vertically
        assertEquals(0, shorter.x(), EPS);
        assertEquals((1080 - 1125 * 1920.0 / 2436) / 2, shorter.y(), EPS);
    }

    @Test
    void zoomStepsGoToTheNextStep() {
        assertEquals(1.25, Zoom.zoomIn(1), EPS);
        assertEquals(0.75, Zoom.zoomOut(1), EPS);
        assertEquals(0.67, Zoom.zoomIn(0.6), EPS);   // from a fit scale to the next step above it
        assertEquals(0.5, Zoom.zoomOut(0.6), EPS);
        assertEquals(2, Zoom.zoomIn(2), EPS);        // stops at the ends: 200% at most
        assertEquals(2, Zoom.zoomIn(1.5), EPS);
        assertEquals(Zoom.MAX_PERCENT, Zoom.STEPS[Zoom.STEPS.length - 1]);
        assertEquals(Zoom.MIN_PERCENT, Zoom.STEPS[0]);
        assertEquals(0.01, Zoom.zoomOut(0.01), EPS);
        assertEquals(0.006, Zoom.zoomOut(0.006), EPS);   // a fit below 1% stays: − never enlarges (#47)
        assertEquals(2.5, Zoom.zoomIn(2.5), EPS);         // + never shrinks
    }

    @Test
    void zoomKeepsTheAnchorPointInPlace() {
        Zoom.View before = start(1, WIDE);   // x = -258, y = 0
        Zoom.View after = Zoom.around(before, 1.25, 960, 540, WIDE[0], WIDE[1], VIEW_W, VIEW_H);
        // Image point under the centre: ((960 + 258) / 1, 540 / 1) before, the same after.
        assertEquals((960 - before.x()) / 1, (960 - after.x()) / 1.25, EPS);
        assertEquals((540 - before.y()) / 1, (540 - after.y()) / 1.25, EPS);
    }

    @Test
    void zoomCentresSidesThatFitAndLeavesNoEmptyBand() {
        Zoom.View big = start(1, WIDE);
        Zoom.View small = Zoom.around(big, 0.5, 0, 0, WIDE[0], WIDE[1], VIEW_W, VIEW_H);   // 1218×562: fits
        assertEquals(new Zoom.View(0.5, (1920 - 1218) / 2.0, (1080 - 562.5) / 2), small);
        Zoom.View corner = Zoom.around(start(1, TALL), 2, 0, 0, TALL[0], TALL[1], VIEW_W, VIEW_H);   // 2400×3600
        assertEquals(0, corner.y(), EPS);
        assertEquals(0, corner.x(), EPS);   // its left edge would move from 360 to 720, leaving a band on the left
        Zoom.View right = Zoom.around(start(1, TALL), 2, 1920, 0, TALL[0], TALL[1], VIEW_W, VIEW_H);
        assertEquals(1920 - 2400, right.x(), EPS);   // and here a band on the right
    }

    @Test
    void sameScaleOnlyTidiesAfterAResize() {
        Zoom.View view = new Zoom.View(1, -400, -900);   // TALL at 100%, scrolled down
        Zoom.View tidy = Zoom.around(view, 1, 0, 0, TALL[0], TALL[1], 1920, 1200);   // window grew taller
        assertEquals(360, tidy.x(), EPS);     // 1200 wide fits in 1920: centred
        assertEquals(-600, tidy.y(), EPS);    // bottom edge kept at the window's bottom
    }

    @Test
    void sizesMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> Zoom.scale(Zoom.Mode.FIT, 100, 0, 10, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> Zoom.start(1, 10, 10, 10, Double.NaN));
    }
}
