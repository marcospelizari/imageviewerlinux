package linview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class AppTest {

    @Test
    void titleIsAppName() {
        assertEquals("linview", App.TITLE);
    }

    /** R-NAV-01: {@code name — n/N — W×H — linview}. */
    @Test
    void titleShowsNamePositionAndSize() {
        assertEquals("img2.jpg \u2014 2/10 \u2014 6000\u00d74000 \u2014 linview", App.title("img2.jpg", 2, 10, 6000, 4000));
    }

    @Test
    void titleLeavesOutSizeWhileLoading() {
        assertEquals("img2.jpg \u2014 2/10 \u2014 linview", App.title("img2.jpg", 2, 10, 0, 0));
    }

    /** #40: a missing icon file would crash the app at start, so check every size is packaged. */
    @Test
    void everyIconSizeIsPackaged() {
        for (int size : App.ICON_SIZES) {
            assertNotNull(App.class.getResource(App.iconPath(size)), "missing icon " + size);
        }
    }
}
