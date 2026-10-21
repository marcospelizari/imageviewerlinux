package linview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class AppTest {

    @Test
    void titleIsAppName() {
        assertEquals("linview", App.TITLE);
    }

    /** #40: a missing icon file would crash the app at start, so check every size is packaged. */
    @Test
    void everyIconSizeIsPackaged() {
        for (int size : App.ICON_SIZES) {
            assertNotNull(App.class.getResource(App.iconPath(size)), "missing icon " + size);
        }
    }
}
