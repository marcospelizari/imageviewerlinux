package linview.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import linview.image.Loaded;
import linview.image.Orientation;
import linview.settings.Settings;
import linview.settings.SettingsStore;

/**
 * The viewing workflow without a screen: images are plain names, loads finish when the test says so, and the
 * display records what it was asked to show. View 1920×1080.
 */
class ViewerControllerTest {

    /** A load the test finishes by hand, in any order. */
    private record Pending(Path file, Consumer<Loaded<String>> done, boolean[] cancelled) {

        void finish(double width, double height) {
            done.accept(new Loaded<>(file.getFileName().toString(), width, height, Orientation.UPRIGHT));
        }
    }

    @TempDir
    Path folder;

    private final List<Pending> loads = new ArrayList<>();
    /** When set, a load finishes before {@code load} returns, as JavaFX does for an image already in memory. */
    private Loaded<String> immediately;
    private final List<String> warnings = new ArrayList<>();
    private String shownImage;
    private Zoom.View shownView;
    private String message;
    private String title;
    private Settings settings;
    private Path settingsFile;
    private ViewerController<String> controller;

    @BeforeEach
    void setUp() throws IOException {
        for (String name : List.of("a.jpg", "b.jpg", "c.jpg")) {
            Files.createFile(folder.resolve(name));   // contents never read: loading is faked
        }
        settingsFile = folder.resolve("config/settings.properties");
        settings = new Settings();
        controller = new ViewerController<>(settings, new SettingsStore(settingsFile, warnings::add),
                (file, done) -> {
                    boolean[] cancelled = {false};
                    loads.add(new Pending(file, done, cancelled));
                    if (immediately != null) {
                        done.accept(immediately);
                    }
                    return () -> cancelled[0] = true;
                },
                new ViewerController.Display<>() {
                    @Override
                    public void showImage(String image, Orientation orientation, Zoom.View view) {
                        shownImage = image;
                        shownView = view;
                        message = null;
                    }

                    @Override
                    public void showMessage(String text) {
                        shownImage = null;
                        message = text;
                    }

                    @Override
                    public void showTitle(String text) {
                        title = text;
                    }
                });
        controller.resize(1920, 1080);
    }

    private Pending lastLoad() {
        return loads.get(loads.size() - 1);
    }

    @Test
    void titleIsAppName() {
        assertEquals("linview", ViewerController.TITLE);
    }

    /** R-NAV-01, R-ZOOM-01: {@code name — n/N — W×H — zoom% — linview}. */
    @Test
    void titleShowsNamePositionSizeAndZoom() {
        assertEquals("img2.jpg — 2/10 — 6000×4000 — 18% — linview",
                ViewerController.title("img2.jpg", 2, 10, 6000, 4000, 1080.0 / 6000));
        assertEquals("img2.jpg — 2/10 — 6000×4000 — linview",
                ViewerController.title("img2.jpg", 2, 10, 6000, 4000, 0));
    }

    @Test
    void titleLeavesOutSizeWhileLoading() {
        assertEquals("img2.jpg — 2/10 — linview", ViewerController.title("img2.jpg", 2, 10, 0, 0, 0));
    }

    @Test
    void openingAFolderLoadsAndShowsItsFirstImage() {
        controller.open(folder);
        assertEquals("a.jpg — 1/3 — linview", title);
        assertNull(shownImage);

        lastLoad().finish(3840, 2160);

        assertEquals("a.jpg", shownImage);
        assertEquals(new Zoom.View(0.5, 0, 0), shownView);
        assertEquals("a.jpg — 1/3 — 3840×2160 — 50% — linview", title);
    }

    @Test
    void aLateResultForAnEarlierImageIsIgnored() {
        controller.open(folder);
        Pending a = lastLoad();
        controller.next();
        Pending b = lastLoad();
        assertTrue(a.cancelled()[0]);

        a.finish(800, 600);   // arrives anyway, after b was asked for
        assertNull(shownImage);
        assertEquals("b.jpg — 2/3 — linview", title);

        b.finish(800, 600);
        assertEquals("b.jpg", shownImage);
        assertFalse(b.cancelled()[0]);
    }

    @Test
    void aLoadThatFinishesBeforeLoadReturnsIsShown() {
        immediately = new Loaded<>("cached", 800, 600, Orientation.UPRIGHT);
        controller.open(folder);
        assertEquals("cached", shownImage);
        assertEquals("a.jpg \u2014 1/3 \u2014 800\u00d7600 \u2014 100% \u2014 linview", title);
    }

    @Test
    void aCancelledLoadMayReportLateOrNever() {
        controller.open(folder);
        Pending a = lastLoad();
        controller.next();
        Pending b = lastLoad();
        controller.next();   // b is cancelled and never reports
        Pending c = lastLoad();
        assertTrue(b.cancelled()[0]);

        c.finish(800, 600);
        a.done().accept(Loaded.failed());   // a cancelled load reporting an error late must not hide c
        assertEquals("c.jpg", shownImage);
        assertNull(message);
    }

    @Test
    void whileTheNextImageLoadsTheOldOneStaysWithTheNewTitle() {
        controller.open(folder);
        lastLoad().finish(800, 600);
        controller.next();
        controller.resize(1000, 800);   // re-places a.jpg, but must not put its size into b.jpg's title
        assertEquals("a.jpg", shownImage);
        assertEquals("b.jpg — 2/3 — linview", title);
    }

    @Test
    void navigationStopsAtTheEndsWithoutReloading() {
        controller.open(folder);
        controller.last();
        int count = loads.size();
        controller.next();
        assertEquals(count, loads.size());
        controller.first();
        controller.previous();
        assertEquals(count + 1, loads.size());
    }

    @Test
    void aFailedLoadShowsAMessageAndNavigationStillWorks() {
        controller.open(folder);
        lastLoad().done().accept(Loaded.failed());
        assertEquals("Can't show a.jpg: the file is damaged or not a supported image", message);
        controller.next();
        lastLoad().finish(800, 600);
        assertEquals("b.jpg", shownImage);
    }

    @Test
    void problemsOpeningAreShownAsMessages() throws IOException {
        controller.open(folder.resolve("missing.jpg"));
        assertEquals("Not found: " + folder.resolve("missing.jpg"), message);
        assertEquals("linview", title);

        Path empty = Files.createDirectory(folder.resolve("empty"));
        controller.open(empty);
        assertEquals("No images in " + empty + "\n\n" + ViewerController.OPEN_HINT, message);
    }

    @Test
    void openHintOnlyWhenNothingIsOpen() {
        controller.showOpenHintIfEmpty();
        assertEquals(ViewerController.OPEN_HINT, message);
        controller.open(folder);
        lastLoad().finish(800, 600);
        controller.showOpenHintIfEmpty();
        assertNull(message);
        assertEquals(folder, controller.startFolder());
    }

    @Test
    void aNewZoomModeAppliesAndIsSaved() throws IOException {
        controller.open(folder);
        lastLoad().finish(3840, 2160);
        controller.setZoomMode(Zoom.Mode.ACTUAL);
        assertEquals(new Zoom.View(1, -960, 0), shownView);   // top-centre (R-ZOOM-03)
        assertTrue(Files.readString(settingsFile).contains("zoom.mode=actual"));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void handZoomLastsUntilTheNextImage() {
        controller.open(folder);
        lastLoad().finish(3840, 2160);
        controller.zoomIn();
        assertEquals(0.67, shownView.scale(), 1e-9);
        controller.resize(1600, 900);   // kept, only tidied
        assertEquals(0.67, shownView.scale(), 1e-9);

        controller.next();
        lastLoad().finish(3840, 2160);
        assertEquals(1600.0 / 3840, shownView.scale(), 1e-9);   // fit again (decided 2026-10-22)
    }

    @Test
    void aFailedSaveWarnsAndTheNewModeStillApplies() throws IOException {
        Files.writeString(folder.resolve("config"), "");   // a file where the settings folder should be
        controller.open(folder);
        lastLoad().finish(3840, 2160);
        controller.setZoomMode(Zoom.Mode.ACTUAL);
        assertEquals(1, warnings.size(), warnings.toString());
        assertEquals(1, shownView.scale(), 1e-9);
        controller.next();
        lastLoad().finish(800, 600);
        assertEquals("b.jpg", shownImage);
    }

    @Test
    void sameModeKeyAgainDropsHandZoomWithoutSaving() {
        controller.open(folder);
        lastLoad().finish(3840, 2160);
        controller.zoomOut();
        controller.setZoomMode(Zoom.Mode.FIT);
        assertEquals(0.5, shownView.scale(), 1e-9);
        assertFalse(Files.exists(settingsFile));
    }

    @Test
    void fitModesFollowTheWindowSize() {
        controller.open(folder);
        lastLoad().finish(3840, 2160);
        controller.resize(960, 540);
        assertEquals(0.25, shownView.scale(), 1e-9);
    }

    @Test
    void aToggleSwitchesBetweenFitAndActualSize() {
        controller.open(folder);
        lastLoad().finish(3840, 2160);
        controller.toggleFitOrActual();
        assertEquals(Zoom.Mode.ACTUAL, settings.zoomMode());
        controller.toggleFitOrActual();
        assertEquals(Zoom.Mode.FIT, settings.zoomMode());
        controller.setZoomMode(Zoom.Mode.WIDTH);
        controller.toggleFitOrActual();
        assertEquals(Zoom.Mode.ACTUAL, settings.zoomMode());
    }

    @Test
    void zoomKeysBeforeAnImageIsShownDoNothing() {
        controller.zoomIn();
        controller.zoomOut();
        assertNull(shownView);
    }
}
