package linview.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import linview.viewer.Zoom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R-SET-01: load, save, defaults, invalid values and failed saves. */
class SettingsStoreTest {

    private final List<String> warnings = new ArrayList<>();

    private Settings load(Path file) {
        return new SettingsStore(file, warnings::add).load();
    }

    private boolean save(Settings settings, Path file) {
        SettingsStore store = new SettingsStore(file, warnings::add);
        store.save(settings);
        return store.flush();
    }

    private static void assertDefaults(Settings s) {
        assertEquals(Zoom.Mode.FIT, s.zoomMode());
        assertEquals(100, s.zoomPercent());
        assertEquals(Settings.Wheel.SCROLL, s.wheel());
        assertEquals("black", s.background());
        assertTrue(s.fileNameOverlay());
        assertFalse(s.fullScreen());
        assertNull(s.windowX());
        assertNull(s.windowY());
        assertEquals(1024, s.windowWidth());
        assertEquals(700, s.windowHeight());
    }

    @Test
    void missingFileGivesDefaultsWithoutWarning(@TempDir Path folder) {
        assertDefaults(load(folder.resolve("settings.properties")));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void unreadableFileGivesDefaultsAndWarns(@TempDir Path folder) {
        assertDefaults(load(folder));   // a folder can't be read as a file
        assertEquals(1, warnings.size());
    }

    @Test
    void savedValuesLoadBack(@TempDir Path folder) throws IOException {
        Settings s = new Settings();
        s.setZoomMode(Zoom.Mode.LOCK);
        s.setZoomPercent(52.5);
        s.setWheel(Settings.Wheel.NAVIGATE);
        s.setBackground("#202020");
        s.setFileNameOverlay(false);
        s.setFullScreen(true);
        s.setWindowPosition(1930.0, -10.0);
        s.setWindowWidth(800);
        s.setWindowHeight(600);
        Path file = folder.resolve("new/linview/settings.properties");   // folders are created

        assertTrue(save(s, file));
        Settings loaded = load(file);

        assertEquals(Zoom.Mode.LOCK, loaded.zoomMode());
        assertEquals(52.5, loaded.zoomPercent());
        assertEquals(Settings.Wheel.NAVIGATE, loaded.wheel());
        assertEquals("#202020", loaded.background());
        assertFalse(loaded.fileNameOverlay());
        assertTrue(loaded.fullScreen());
        assertEquals(1930.0, loaded.windowX());
        assertEquals(-10.0, loaded.windowY());
        assertEquals(800, loaded.windowWidth());
        assertEquals(600, loaded.windowHeight());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void savedDefaultsLoadBackAsDefaults(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("settings.properties");
        assertTrue(save(new Settings(), file));
        assertDefaults(load(file));
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertTrue(Files.readString(file).contains("zoom.percent=100\n"));
    }

    @Test
    void invalidValuesFallBackToDefaultsAndWarnEach(@TempDir Path folder) throws IOException {
        Path file = Files.writeString(folder.resolve("settings.properties"), String.join("\n",
                "zoom.mode=zoomy",
                "zoom.percent=0",
                "wheel=sideways",
                "background=#12345",
                "overlay.fileName=1",
                "start.fullScreen=maybe",
                "window.x=left",
                "window.y=10",
                "window.width=50",
                "window.height=tall"));

        assertDefaults(load(file));
        assertEquals(9, warnings.size(), warnings.toString());   // window.y is valid, but useless without x
    }

    @Test
    void valuesAreTrimmedAndCaseInsensitive(@TempDir Path folder) throws IOException {
        Path file = Files.writeString(folder.resolve("settings.properties"),
                "zoom.mode = Width \nwheel=NAVIGATE\nbackground= DarkGrey\noverlay.fileName=FALSE\n");
        Settings s = load(file);
        assertEquals(Zoom.Mode.WIDTH, s.zoomMode());
        assertEquals(Settings.Wheel.NAVIGATE, s.wheel());
        assertEquals("darkgrey", s.background());
        assertFalse(s.fileNameOverlay());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    /** zoom.enlargeSmall was removed on 2026-10-22: a file that still has it loads and drops it on the next save. */
    @Test
    void removedKeysAreIgnored(@TempDir Path folder) throws IOException {
        Path file = Files.writeString(folder.resolve("settings.properties"),
                "zoom.enlargeSmall=true\nwheel=navigate\n");
        Settings s = load(file);
        assertEquals(Settings.Wheel.NAVIGATE, s.wheel());
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertTrue(save(s, file));
        assertFalse(Files.readString(file).contains("enlargeSmall"));
    }

    @Test
    void zoomPercentLimits(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("settings.properties");
        Files.writeString(file, "zoom.percent=200");
        assertEquals(200, load(file).zoomPercent());
        Files.writeString(file, "zoom.percent=201");
        assertEquals(100, load(file).zoomPercent());
        Files.writeString(file, "zoom.percent=NaN");
        assertEquals(100, load(file).zoomPercent());
        assertEquals(2, warnings.size());
    }

    @Test
    void failedSaveKeepsTheOldFile(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("settings.properties");
        Settings s = new Settings();
        s.setWheel(Settings.Wheel.NAVIGATE);
        assertTrue(save(s, file));
        String before = Files.readString(file);

        assertTrue(folder.toFile().setWritable(false));
        boolean saved;
        try {
            saved = save(new Settings(), file);   // the folder is read-only: nothing may change
        } finally {
            folder.toFile().setWritable(true);
        }
        assumeTrue(!saved, "root ignores read-only folders (CI container)");
        assertEquals(1, warnings.size(), warnings.toString());

        assertEquals(before, Files.readString(file));
        try (var files = Files.list(folder)) {
            assertEquals(List.of(file), files.toList());   // no temporary file left behind
        }
    }

    @Test
    void saveIntoAFileInsteadOfAFolderFailsWithAWarning(@TempDir Path folder) throws IOException {
        Path blocker = Files.writeString(folder.resolve("linview"), "");
        assertFalse(save(new Settings(), blocker.resolve("settings.properties")));
        assertEquals(1, warnings.size(), warnings.toString());
    }

    /** #52: quick saves in a row end in one write of the newest values. */
    @Test
    void quickSavesInARowAreWrittenOnceWithTheNewestValues(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("settings.properties");
        SettingsStore store = new SettingsStore(file, warnings::add);
        Settings s = new Settings();
        s.setZoomMode(Zoom.Mode.LOCK);
        synchronized (store) {   // the writer waits, as during a slow write
            for (int percent = 51; percent <= 100; percent++) {
                s.setZoomPercent(percent);
                store.save(s);
            }
        }

        assertTrue(store.flush());

        assertEquals(1, store.writes.get());
        assertEquals(100, load(file).zoomPercent());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    /** The values are copied when saving: a change after {@code save} is not written by it. */
    @Test
    void saveCopiesTheValues(@TempDir Path folder) {
        Path file = folder.resolve("settings.properties");
        SettingsStore store = new SettingsStore(file, warnings::add);
        Settings s = new Settings();
        synchronized (store) {
            s.setWheel(Settings.Wheel.NAVIGATE);
            store.save(s);
            s.setWheel(Settings.Wheel.SCROLL);
        }

        store.flush();

        assertEquals(Settings.Wheel.NAVIGATE, load(file).wheel());
    }

    /** Closing writes a change still pending, so nothing is lost on a normal close. */
    @Test
    void closeWritesWhatIsPending(@TempDir Path folder) {
        Path file = folder.resolve("settings.properties");
        SettingsStore store = new SettingsStore(file, warnings::add);
        Settings s = new Settings();
        s.setZoomPercent(42);
        store.save(s);

        store.close();

        assertEquals(42, load(file).zoomPercent());
    }

    @Test
    void flushWithNothingSavedWritesNothing(@TempDir Path folder) {
        SettingsStore store = new SettingsStore(folder.resolve("settings.properties"), warnings::add);
        assertTrue(store.flush());
        assertEquals(0, store.writes.get());
        assertFalse(Files.exists(folder.resolve("settings.properties")));
    }
}
