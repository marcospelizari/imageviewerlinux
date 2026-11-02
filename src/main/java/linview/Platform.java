package linview;

import java.util.Locale;

/**
 * The one check for the operating system (#70, #78): Linux is the default, Windows differs in the Trash, a saved
 * file's access rights and where settings and thumbnails live. No other platform is planned (macOS parked).
 */
public final class Platform {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private Platform() {
    }

    public static boolean windows() {
        return WINDOWS;
    }
}
