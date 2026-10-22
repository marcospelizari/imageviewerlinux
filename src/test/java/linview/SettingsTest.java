package linview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** R-SET-01: load, save, defaults and invalid values. */
class SettingsTest {

    private final List<String> warnings = new ArrayList<>();

    private Settings load(Path file) {
        return Settings.load(file, warnings::add);
    }

    private static void assertDefaults(Settings s) {
        assertEquals(Settings.ZoomMode.FIT, s.zoomMode);
        assertEquals(100, s.zoomPercent);
        assertFalse(s.enlargeSmall);
        assertEquals(Settings.Wheel.SCROLL, s.wheel);
        assertEquals("black", s.background);
        assertTrue(s.fileNameOverlay);
        assertFalse(s.fullScreen);
        assertNull(s.windowX);
        assertNull(s.windowY);
        assertEquals(1024, s.windowWidth);
        assertEquals(700, s.windowHeight);
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
        s.zoomMode = Settings.ZoomMode.LOCK;
        s.zoomPercent = 52.5;
        s.enlargeSmall = true;
        s.wheel = Settings.Wheel.NAVIGATE;
        s.background = "#202020";
        s.fileNameOverlay = false;
        s.fullScreen = true;
        s.windowX = 1930.0;
        s.windowY = -10.0;
        s.windowWidth = 800;
        s.windowHeight = 600;
        Path file = folder.resolve("new/linview/settings.properties");   // folders are created

        s.save(file);
        Settings loaded = load(file);

        assertEquals(Settings.ZoomMode.LOCK, loaded.zoomMode);
        assertEquals(52.5, loaded.zoomPercent);
        assertTrue(loaded.enlargeSmall);
        assertEquals(Settings.Wheel.NAVIGATE, loaded.wheel);
        assertEquals("#202020", loaded.background);
        assertFalse(loaded.fileNameOverlay);
        assertTrue(loaded.fullScreen);
        assertEquals(1930.0, loaded.windowX);
        assertEquals(-10.0, loaded.windowY);
        assertEquals(800, loaded.windowWidth);
        assertEquals(600, loaded.windowHeight);
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void savedDefaultsLoadBackAsDefaults(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("settings.properties");
        new Settings().save(file);
        assertDefaults(load(file));
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertTrue(Files.readString(file).contains("zoom.percent=100\n"));
    }

    @Test
    void invalidValuesFallBackToDefaultsAndWarnEach(@TempDir Path folder) throws IOException {
        Path file = Files.writeString(folder.resolve("settings.properties"), String.join("\n",
                "zoom.mode=zoomy",
                "zoom.percent=0",
                "zoom.enlargeSmall=yes",
                "wheel=sideways",
                "background=#12345",
                "overlay.fileName=1",
                "start.fullScreen=maybe",
                "window.x=left",
                "window.y=10",
                "window.width=50",
                "window.height=tall"));

        assertDefaults(load(file));
        assertEquals(10, warnings.size(), warnings.toString());   // window.y is valid, but useless without x
    }

    @Test
    void valuesAreTrimmedAndCaseInsensitive(@TempDir Path folder) throws IOException {
        Path file = Files.writeString(folder.resolve("settings.properties"),
                "zoom.mode = Width \nwheel=NAVIGATE\nbackground= DarkGrey\nzoom.enlargeSmall=TRUE\n");
        Settings s = load(file);
        assertEquals(Settings.ZoomMode.WIDTH, s.zoomMode);
        assertEquals(Settings.Wheel.NAVIGATE, s.wheel);
        assertEquals("darkgrey", s.background);
        assertTrue(s.enlargeSmall);
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void zoomPercentLimits(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("settings.properties");
        Files.writeString(file, "zoom.percent=3200");
        assertEquals(3200, load(file).zoomPercent);
        Files.writeString(file, "zoom.percent=3201");
        assertEquals(100, load(file).zoomPercent);
        Files.writeString(file, "zoom.percent=NaN");
        assertEquals(100, load(file).zoomPercent);
        assertEquals(2, warnings.size());
    }

    @Test
    void failedSaveKeepsTheOldFile(@TempDir Path folder) throws IOException {
        Path file = folder.resolve("settings.properties");
        Settings s = new Settings();
        s.wheel = Settings.Wheel.NAVIGATE;
        s.save(file);
        String before = Files.readString(file);

        assertTrue(folder.toFile().setWritable(false));
        boolean failed = false;
        try {
            new Settings().save(file);
        } catch (IOException expected) {
            failed = true;   // the folder is read-only: nothing may change
        } finally {
            folder.toFile().setWritable(true);
        }
        assumeTrue(failed, "root ignores read-only folders (CI container)");

        assertEquals(before, Files.readString(file));
        try (var files = Files.list(folder)) {
            assertEquals(List.of(file), files.toList());   // no temporary file left behind
        }
    }

    @Test
    void saveIntoAFileInsteadOfAFolderFails(@TempDir Path folder) throws IOException {
        Path blocker = Files.writeString(folder.resolve("linview"), "");
        assertThrows(IOException.class, () -> new Settings().save(blocker.resolve("settings.properties")));
    }
}
