package linview.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class ViewerWindowTest {

    /** #40: a missing icon file would crash the app at start, so check every size is packaged. */
    @Test
    void everyIconSizeIsPackaged() {
        for (int size : ViewerWindow.ICON_SIZES) {
            assertNotNull(ViewerWindow.class.getResource(ViewerWindow.iconPath(size)), "missing icon " + size);
        }
    }
}
