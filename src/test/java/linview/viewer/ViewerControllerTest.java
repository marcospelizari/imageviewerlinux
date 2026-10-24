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
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
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
    private record Pending(Path file, double minResolution, Consumer<Loaded<String>> done, boolean[] cancelled) {

        boolean full() {
            return minResolution >= 1;
        }


        void finish(double width, double height) {
            done.accept(new Loaded<>(file.getFileName().toString(), width, height, Orientation.UPRIGHT));
        }

        /** A copy with {@code resolution} decoded pixels per real pixel, named "{@code name} copy" or "… full". */
        void finish(double width, double height, double resolution) {
            String kind = resolution < 1 ? " copy" : " full";
            done.accept(new Loaded<>(file.getFileName() + kind, width, height, Orientation.UPRIGHT, resolution, 0));
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
    private Orientation shownOrientation;
    private String message;
    private String title;
    private Settings settings;
    private Path settingsFile;
    /** The settings writer; tests {@code flush} it before looking at the file (#52). */
    private SettingsStore store;
    private ViewerController<String> controller;
    private final List<String> timingLines = new ArrayList<>();
    /** What the controller asked to preload, one list per call (#21). */
    private final List<List<String>> preloads = new ArrayList<>();
    private double preloadResolution;
    /** The timing log's clock, in nanoseconds. */
    private long now;

    @BeforeEach
    void setUp() throws IOException {
        for (String name : List.of("a.jpg", "b.jpg", "c.jpg")) {
            Files.createFile(folder.resolve(name));   // contents never read: loading is faked
        }
        settingsFile = folder.resolve("config/settings.properties");
        settings = new Settings();
        store = new SettingsStore(settingsFile, warnings::add);
        controller = newController(Runnable::run);
        controller.resize(1920, 1080);
    }

    /** A controller over the fakes; {@code background} runs folder listings (#21). */
    private ViewerController<String> newController(Executor background) {
        return new ViewerController<>(settings, store,
                new ViewerController.ImageSource<>() {
                    @Override
                    public Runnable load(Path file, double minResolution, Consumer<Loaded<String>> done) {
                        boolean[] cancelled = {false};
                        loads.add(new Pending(file, minResolution, done, cancelled));
                        if (immediately != null) {
                            done.accept(immediately);
                        }
                        return () -> cancelled[0] = true;
                    }

                    @Override
                    public void preload(List<Path> files, double minResolution) {
                        preloads.add(files.stream().map(f -> f.getFileName().toString()).toList());
                        preloadResolution = minResolution;
                    }
                },
                new ViewerController.Display<>() {
                    @Override
                    public void showImage(Loaded<String> image, Zoom.View view) {
                        shownImage = image.image();
                        shownView = view;
                        shownOrientation = image.orientation();
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
                }, new TimingLog(timingLines::add, () -> now), background, Runnable::run);
    }

    /** Lets a background write finish before the temporary folder is removed. */
    @AfterEach
    void closeStore() {
        store.close();
    }

    private Pending lastLoad() {
        return loads.get(loads.size() - 1);
    }

    /** #21: after each image the next ones are named for preloading, in the direction of movement. */
    @Test
    void preloadingFollowsTheDirectionOfMovement() {
        controller.open(folder);
        assertEquals(List.of("b.jpg", "c.jpg"), preloads.get(0));   // from the first image: ahead only

        controller.last();
        assertEquals(List.of("b.jpg", "a.jpg"), preloads.get(1));   // backward from c

        controller.previous();
        assertEquals(List.of("a.jpg", "c.jpg"), preloads.get(2));   // one more back, one behind

        controller.next();
        assertEquals(List.of("b.jpg"), preloads.get(3));   // at the last image: nothing ahead, b behind
    }

    /** Preloads are decoded like the image on screen would be, for the zoom mode (#19, #21). */
    @Test
    void preloadsAskForWhatTheZoomModeNeeds() {
        controller.open(folder);
        assertEquals(0, preloadResolution);

        controller.setZoomMode(Zoom.Mode.ACTUAL);
        controller.next();

        assertEquals(1, preloadResolution);
    }

    @Test
    void nothingOpenStopsPreloading() {
        controller.open(folder);
        controller.open(folder.resolve("missing"));

        assertEquals(List.of(), preloads.get(preloads.size() - 1));
    }

    /** #21: listing runs in the background; the image on screen stays and can be navigated meanwhile. */
    @Test
    void aFolderIsListedInTheBackground() throws IOException {
        List<Runnable> listings = new ArrayList<>();
        controller = newController(listings::add);
        controller.resize(1920, 1080);
        controller.open(folder);
        listings.remove(0).run();
        lastLoad().finish(800, 600);

        Path other = Files.createDirectory(folder.resolve("other"));
        Files.createFile(other.resolve("x.jpg"));
        controller.open(other);
        assertEquals("a.jpg", shownImage);   // nothing changes until it is listed
        controller.next();
        lastLoad().finish(800, 600);
        assertEquals("b.jpg", shownImage);

        listings.remove(0).run();
        lastLoad().finish(800, 600);

        assertEquals("x.jpg", shownImage);
        assertEquals("x.jpg — 1/1 — 800×600 — 100% — linview", title);
    }

    /** A listing for an earlier open that finishes late is ignored. */
    @Test
    void aFolderOpenedEarlierIsIgnoredWhenItsListingFinishesLate() throws IOException {
        List<Runnable> listings = new ArrayList<>();
        controller = newController(listings::add);
        controller.resize(1920, 1080);
        Path other = Files.createDirectory(folder.resolve("other"));
        Files.createFile(other.resolve("x.jpg"));

        controller.open(folder);
        controller.open(other);
        listings.get(1).run();   // the later open finishes first
        listings.get(0).run();

        assertEquals(List.of("x.jpg"), loads.stream().map(l -> l.file().getFileName().toString()).toList());
    }

    /** #19: a screen-size copy that is sharp enough for the zoom needs nothing more. */
    @Test
    void aScreenSizeCopyAtFitLoadsNothingMore() {
        controller.open(folder);
        assertFalse(lastLoad().full());
        lastLoad().finish(3840, 2160, 0.5);   // decoded at 1920×1080

        assertEquals("a.jpg copy", shownImage);
        assertEquals(new Zoom.View(0.5, 0, 0), shownView);
        assertEquals("a.jpg — 1/3 — 3840×2160 — 50% — linview", title);   // the real size, not the copy's
        assertEquals(1, loads.size());
    }

    /** #22: actual size needs more pixels than the copy: the full image is loaded once and swapped in place. */
    @Test
    void actualSizeLoadsTheFullImageOnceAndSwapsItIn() {
        controller.open(folder);
        lastLoad().finish(3840, 2160, 0.5);
        controller.setZoomMode(Zoom.Mode.ACTUAL);   // 1 pressed once the copy is on screen
        assertEquals("a.jpg copy", shownImage);   // stretched until the full one arrives
        Pending full = lastLoad();
        assertTrue(full.full());
        assertEquals(2, loads.size());

        controller.pan(-100, -50);
        controller.zoomOut();
        controller.zoomIn();
        assertEquals(2, loads.size());   // asked for once
        Zoom.View before = shownView;

        full.finish(3840, 2160, 1);

        assertEquals("a.jpg full", shownImage);
        assertEquals(before, shownView);   // same place and zoom
        assertEquals("a.jpg — 1/3 — 3840×2160 — 100% — linview", title);
    }

    /** Each zoom mode asks the loader at once for what it will need, so no image is decoded twice. */
    @Test
    void theFirstLoadAsksForWhatTheZoomModeNeeds() {
        controller.open(folder);
        assertEquals(0, lastLoad().minResolution());   // fit: the screen-size copy

        controller.setZoomMode(Zoom.Mode.ACTUAL);
        controller.next();
        assertEquals(1, lastLoad().minResolution());

        settings.setZoomPercent(70);
        controller.setZoomMode(Zoom.Mode.LOCK);
        controller.next();
        assertEquals(0.7, lastLoad().minResolution());
    }

    @Test
    void zoomingInPastTheCopyLoadsTheFullImage() {
        controller.open(folder);
        lastLoad().finish(3840, 2160, 0.5);
        controller.zoomOut();
        assertEquals(1, loads.size());

        controller.zoomIn();
        controller.zoomIn();

        assertTrue(lastLoad().full());
        assertEquals(2, loads.size());
    }

    /** The full image belongs to one image: moving on cancels it, and a late one is ignored. */
    @Test
    void theFullImageOfAPreviousImageIsCancelledAndIgnored() {
        controller.open(folder);
        lastLoad().finish(3840, 2160, 0.5);
        controller.setZoomMode(Zoom.Mode.ACTUAL);   // 1 pressed once the copy is on screen
        Pending full = lastLoad();

        controller.next();
        assertTrue(full.cancelled()[0]);
        lastLoad().finish(800, 600);
        full.finish(3840, 2160, 1);

        assertEquals("b.jpg", shownImage);
    }

    @Test
    void aFailedFullImageKeepsTheCopy() {
        controller.open(folder);
        lastLoad().finish(3840, 2160, 0.5);
        controller.setZoomMode(Zoom.Mode.ACTUAL);   // 1 pressed once the copy is on screen

        lastLoad().done().accept(Loaded.failed());

        assertEquals("a.jpg copy", shownImage);
        assertNull(message);
    }

    /** A rotation made while the full image loads stays when it arrives. */
    @Test
    void theFullImageKeepsARotation() {
        controller.open(folder);
        lastLoad().finish(3840, 2160, 0.5);
        controller.setZoomMode(Zoom.Mode.ACTUAL);   // 1 pressed once the copy is on screen
        Pending full = lastLoad();
        controller.rotateRight();
        Orientation turned = shownOrientation;

        full.finish(3840, 2160, 1);

        assertEquals("a.jpg full", shownImage);
        assertEquals(turned, shownOrientation);
        assertEquals(2, loads.size());   // the rotation asked for nothing new
    }

    @Test
    void timingLogsTheFullImage() {
        controller.open(folder);
        lastLoad().finish(3840, 2160, 0.5);
        controller.setZoomMode(Zoom.Mode.ACTUAL);   // 1 pressed once the copy is on screen
        lastLoad().done().accept(new Loaded<>("a.jpg", 3840, 2160, Orientation.UPRIGHT, 1, 250));

        assertEquals("timing  a.jpg  full resolution, load 250.0 ms", timingLines.get(1));
    }

    /** #23: shown = from asking for the image until it's handed to the window; load = the loader's own time. */
    @Test
    void timingLogsEachImageOnceWhenShown() {
        now = 1_000_000_000L;
        controller.open(folder);
        now += 120_000_000L;
        lastLoad().done().accept(new Loaded<>("a.jpg", 800, 600, Orientation.UPRIGHT, 1, 100));

        controller.zoomIn();
        controller.rotateRight();

        assertEquals(List.of("timing  a.jpg  shown 120.0 ms  load 100.0 ms"), timingLines);
    }

    @Test
    void timingIgnoresAnImageNoLongerWanted() {
        controller.open(folder);
        Pending first = lastLoad();
        now = 50_000_000L;
        controller.next();
        now = 80_000_000L;
        first.finish(800, 600);
        assertEquals(List.of(), timingLines);

        lastLoad().finish(800, 600);

        assertEquals(List.of("timing  b.jpg  shown 30.0 ms  load 0.0 ms"), timingLines);
    }

    @Test
    void timingLogsAFailedLoad() {
        controller.open(folder);
        now = 7_500_000L;
        lastLoad().done().accept(Loaded.failed());

        assertEquals(List.of("timing  a.jpg  failed after 7.5 ms"), timingLines);
    }

    @Test
    void titleIsAppName() {
        assertEquals("linview", ViewerController.TITLE);
    }

    /** R-NAV-01, R-ZOOM-01: {@code name — n/N — W×H — zoom% — linview}. */
    @Test
    void titleShowsNamePositionSizeAndZoom() {
        assertEquals("img2.jpg — 2/10 — 6000×4000 — 18% — linview",
                ViewerController.title("img2.jpg", 2, 10, 6000, 4000, 1080.0 / 6000, false));
        assertEquals("img2.jpg — 2/10 — 6000×4000 — linview",
                ViewerController.title("img2.jpg", 2, 10, 6000, 4000, 0, false));
        assertEquals("img2.jpg — 2/10 — 6000×4000 — 70% (locked) — linview",
                ViewerController.title("img2.jpg", 2, 10, 6000, 4000, 0.7, true));
    }

    @Test
    void titleLeavesOutSizeWhileLoading() {
        assertEquals("img2.jpg — 2/10 — linview", ViewerController.title("img2.jpg", 2, 10, 0, 0, 0, false));
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

    /** R-VIEW-01: R turns the view, width and height swap, the zoom starts again from fit. */
    @Test
    void rotateTurnsTheViewAndRefits() {
        controller.open(folder);
        lastLoad().finish(3840, 2160);
        controller.rotateRight();
        assertEquals(new Orientation(90, false), shownOrientation);
        assertEquals(1080.0 / 3840, shownView.scale(), 1e-9);   // now 2160×3840, fitted to the height
        assertTrue(title.contains("2160×3840"), title);
        controller.rotateLeft();
        controller.rotateLeft();
        assertEquals(new Orientation(270, false), shownOrientation);
        assertEquals(0, loads.size() - 1, "no reload");
    }

    @Test
    void rotateDropsHandZoomAndKeepsTheLock() {
        controller.open(folder);
        lastLoad().finish(3840, 2160);
        controller.zoomIn();
        controller.rotateRight();
        assertEquals(1080.0 / 3840, shownView.scale(), 1e-9);

        settings.setZoomMode(Zoom.Mode.LOCK);
        settings.setZoomPercent(70);
        controller.rotateRight();
        assertEquals(0.7, shownView.scale(), 1e-9);
        assertEquals(0, shownView.y(), 1e-9);   // larger than the window: starts at the top (R-ZOOM-03)
    }

    @Test
    void theNextImageIsNotRotated() {
        controller.open(folder);
        lastLoad().finish(3840, 2160);
        controller.rotateRight();
        controller.next();
        lastLoad().finish(3840, 2160);
        assertEquals(Orientation.UPRIGHT, shownOrientation);
        controller.previous();
        lastLoad().finish(3840, 2160);
        assertEquals(Orientation.UPRIGHT, shownOrientation);   // not remembered either
    }

    /** Review R7: while b loads, a is still on screen but no longer selected; R / L do nothing. */
    @Test
    void rotateWaitsWhileTheNextImageLoads() {
        controller.open(folder);
        lastLoad().finish(3840, 2160);
        controller.next();
        controller.rotateRight();
        assertEquals(Orientation.UPRIGHT, shownOrientation);
        lastLoad().finish(800, 600);
        assertEquals("b.jpg", shownImage);
        assertEquals(Orientation.UPRIGHT, shownOrientation);
    }

    @Test
    void rotateWithoutAnImageDoesNothing() {
        controller.rotateRight();
        assertNull(shownImage);
        controller.open(folder);
        lastLoad().done().accept(Loaded.failed());
        controller.rotateRight();
        assertNull(shownImage);
    }

    @Test
    void aNewZoomModeAppliesAndIsSaved() throws IOException {
        controller.open(folder);
        lastLoad().finish(3840, 2160);
        controller.setZoomMode(Zoom.Mode.ACTUAL);
        assertEquals(new Zoom.View(1, -960, 0), shownView);   // top-centre (R-ZOOM-03)
        store.flush();
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
        store.flush();
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
        store.flush();
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

    /** R-ZOOM-02, R-ZOOM-03: locked at 70%, the next image opens at 70% and at its top-centre. */
    @Test
    void zoomLockKeepsThePercentForTheNextImages() throws IOException {
        controller.open(folder);
        lastLoad().finish(3840, 2160);   // fit: 50%
        controller.zoomIn();             // 67%
        controller.zoomIn();             // 75%
        controller.toggleZoomLock();
        assertEquals(Zoom.Mode.LOCK, settings.zoomMode());
        assertEquals(75, settings.zoomPercent());
        assertTrue(title.contains("75% (locked)"), title);
        store.flush();
        assertTrue(Files.readString(settingsFile).contains("zoom.mode=lock\nzoom.percent=75\n"));

        controller.zoomOut();            // locked, so this changes the locked % too
        assertEquals(67, settings.zoomPercent());
        store.flush();
        assertTrue(Files.readString(settingsFile).contains("zoom.percent=67\n"));

        controller.next();
        lastLoad().finish(1200, 1800);   // tall: 804×1206 at 67% is taller than the view
        assertEquals(new Zoom.View(0.67, (1920 - 1200 * 0.67) / 2, 0), shownView);
        controller.next();
        lastLoad().finish(3840, 2160);   // larger both ways: top-centre
        assertEquals(0.67, shownView.scale(), 1e-9);
        assertEquals(0, shownView.y(), 1e-9);
        assertEquals((1920 - 3840 * 0.67) / 2, shownView.x(), 1e-9);
    }

    @Test
    void zoomLockAgainReturnsToTheModeBefore() {
        controller.open(folder);
        lastLoad().finish(3840, 2160);
        controller.setZoomMode(Zoom.Mode.WIDTH);
        controller.toggleZoomLock();
        controller.toggleZoomLock();
        assertEquals(Zoom.Mode.WIDTH, settings.zoomMode());
        assertFalse(title.contains("locked"), title);
    }

    @Test
    void zoomLockAfterARestartReturnsToFit() {
        settings.setZoomMode(Zoom.Mode.LOCK);   // as loaded from the settings file
        settings.setZoomPercent(70);
        controller.open(folder);
        lastLoad().finish(3840, 2160);
        assertEquals(0.7, shownView.scale(), 1e-9);
        controller.toggleZoomLock();
        assertEquals(Zoom.Mode.FIT, settings.zoomMode());
        assertEquals(0.5, shownView.scale(), 1e-9);
    }

    @Test
    void modeKeysLeaveTheLock() {
        controller.open(folder);
        lastLoad().finish(3840, 2160);
        controller.toggleZoomLock();
        controller.toggleFitOrActual();
        assertEquals(Zoom.Mode.ACTUAL, settings.zoomMode());
    }

    @Test
    void handZoomWithoutLockDoesNotChangeTheLockedPercent() {
        settings.setZoomPercent(70);
        controller.open(folder);
        lastLoad().finish(3840, 2160);
        controller.zoomIn();
        assertEquals(70, settings.zoomPercent());
        store.flush();
        assertFalse(Files.exists(settingsFile));
    }

    @Test
    void nothingToLockWithoutAnImage() {
        controller.toggleZoomLock();
        assertEquals(Zoom.Mode.FIT, settings.zoomMode());
        store.flush();
        assertFalse(Files.exists(settingsFile));
    }

    @Test
    void zoomKeysBeforeAnImageIsShownDoNothing() {
        controller.zoomIn();
        controller.zoomOut();
        controller.zoomInAt(100, 100);
        controller.pan(10, 10);
        assertNull(shownView);
    }

    /** R-ZOOM-05: Ctrl + wheel keeps the image point under the mouse where it is. */
    @Test
    void zoomAtThePointerKeepsThatPointUnderIt() {
        controller.open(folder);
        lastLoad().finish(3840, 2160);   // fit: 50%, image point (960, 540) is at (480, 270)
        controller.zoomInAt(480, 270);
        assertEquals(0.67, shownView.scale(), 1e-9);
        assertEquals(960, (480 - shownView.x()) / shownView.scale(), 1e-9);
        assertEquals(540, (270 - shownView.y()) / shownView.scale(), 1e-9);

        controller.zoomOutAt(480, 270);   // fits again: centred
        assertEquals(new Zoom.View(0.5, 0, 0), shownView);
    }

    @Test
    void zoomAtThePointerWithLockSavesThePercent() throws IOException {
        controller.open(folder);
        lastLoad().finish(3840, 2160);
        controller.toggleZoomLock();      // 50%
        controller.zoomInAt(100, 100);
        assertEquals(67, settings.zoomPercent());
        store.flush();
        assertTrue(Files.readString(settingsFile).contains("zoom.percent=67\n"));
    }

    /** R-ZOOM-05: a drag moves the image only on a side where it's larger, and never past its edges. */
    @Test
    void dragMovesOnlyWhereLargerAndStopsAtTheEdges() {
        controller.open(folder);
        lastLoad().finish(1200, 1800);
        controller.setZoomMode(Zoom.Mode.ACTUAL);   // narrower than the view, taller than it
        assertEquals(new Zoom.View(1, 360, 0), shownView);

        controller.pan(100, -300);
        assertEquals(new Zoom.View(1, 360, -300), shownView);
        controller.pan(0, -10_000);
        assertEquals(new Zoom.View(1, 360, 1080 - 1800), shownView);   // bottom edge
        controller.pan(0, 10_000);
        assertEquals(new Zoom.View(1, 360, 0), shownView);              // top edge
    }

    @Test
    void dragDoesNothingWhenTheImageFits() {
        controller.open(folder);
        lastLoad().finish(800, 600);
        Zoom.View before = shownView;
        controller.pan(50, 50);
        assertEquals(before, shownView);
    }

    /** Decided 2026-10-22: after a drag a resize keeps the middle of the window; the scale follows the mode. */
    @Test
    void aResizeAfterADragKeepsTheMiddle() {
        controller.setZoomMode(Zoom.Mode.WIDTH);
        controller.open(folder);
        lastLoad().finish(3840, 4320);   // 50%: 1920×2160, taller than the view
        controller.pan(0, -400);         // image point y 1880 in the middle: (540 + 400) / 0.5
        controller.resize(1600, 1080);

        double scale = 1600.0 / 3840;
        assertEquals(scale, shownView.scale(), 1e-9);
        assertEquals(540 - 1880 * scale, shownView.y(), 1e-9);
        assertEquals(0, shownView.x(), 1e-9);
    }

    @Test
    void aResizeWithoutDragOrZoomStartsAgainAtTheTop() {
        controller.setZoomMode(Zoom.Mode.ACTUAL);
        controller.open(folder);
        lastLoad().finish(1200, 1800);
        controller.resize(1600, 900);
        assertEquals(new Zoom.View(1, 200, 0), shownView);
    }

    /** R-WHEEL-01 scroll: one notch = 10% of the window height; stops at the edges, never changes image. */
    @Test
    void scrollWheelMovesTheImageAndStopsAtTheEdges() {
        controller.setZoomMode(Zoom.Mode.ACTUAL);
        controller.open(folder);
        lastLoad().finish(1200, 1800);   // narrower than the view, taller than it
        controller.wheel(0, -1);         // wheel down: further down the image
        assertEquals(new Zoom.View(1, 360, -108), shownView);
        controller.wheel(-1, 0);         // sideways: the image fits that way
        assertEquals(new Zoom.View(1, 360, -108), shownView);
        int count = loads.size();
        controller.wheel(0, -100);
        assertEquals(new Zoom.View(1, 360, 1080 - 1800), shownView);
        controller.wheel(0, 100);
        assertEquals(new Zoom.View(1, 360, 0), shownView);
        assertEquals(count, loads.size());
    }

    @Test
    void scrollWheelDoesNothingWhenTheImageFits() {
        controller.open(folder);
        lastLoad().finish(800, 600);
        Zoom.View before = shownView;
        controller.wheel(0, -1);
        assertEquals(before, shownView);
    }

    /** R-WHEEL-01 navigate: down = next, up = previous; a touchpad's small movements add up to whole notches. */
    @Test
    void navigateWheelMovesThroughTheFolder() {
        settings.setWheel(Settings.Wheel.NAVIGATE);
        controller.open(folder);
        lastLoad().finish(800, 600);
        controller.wheel(0, -1);
        assertEquals("b.jpg — 2/3 — linview", title);
        controller.wheel(0, 1);
        assertEquals("a.jpg — 1/3 — linview", title);

        controller.wheel(0, -0.4);
        controller.wheel(0, -0.4);
        assertEquals("a.jpg — 1/3 — linview", title);
        controller.wheel(0, -0.4);
        assertEquals("b.jpg — 2/3 — linview", title);

        controller.wheel(0, -5);         // stops at the last image
        assertEquals("c.jpg — 3/3 — linview", title);
    }

    /** #47: a half notch of Ctrl + wheel must not count towards navigating, nor the other way round. */
    @Test
    void zoomAndNavigateDoNotShareWheelLeftovers() {
        settings.setWheel(Settings.Wheel.NAVIGATE);
        controller.open(folder);
        lastLoad().finish(3840, 2160);
        controller.zoomWheel(-0.6, 960, 540);   // towards zoom out, not a whole notch
        controller.wheel(0, -0.5);              // towards next: together they would make one notch
        assertEquals("a.jpg — 1/3 — 3840×2160 — 50% — linview", title);
        controller.zoomWheel(-0.5, 960, 540);
        assertEquals(0.5, shownView.scale(), 1e-9);
    }

    @Test
    void zoomOutNeverEnlargesAFitBelowOnePercent() {
        controller.open(folder);
        lastLoad().finish(320_000, 1000);   // fit: 0.6%
        double fit = shownView.scale();
        controller.zoomOut();
        assertEquals(fit, shownView.scale(), 1e-12);
    }

    @Test
    void ctrlWheelZoomsWhateverTheSetting() {
        settings.setWheel(Settings.Wheel.NAVIGATE);
        controller.open(folder);
        lastLoad().finish(3840, 2160);   // fit: 50%
        controller.zoomWheel(1, 960, 540);
        assertEquals(0.67, shownView.scale(), 1e-9);
        controller.zoomWheel(0.5, 960, 540);
        assertEquals(0.67, shownView.scale(), 1e-9);
        controller.zoomWheel(0.5, 960, 540);
        assertEquals(0.75, shownView.scale(), 1e-9);
        controller.zoomWheel(0.5, 960, 540);
        controller.zoomWheel(-0.5, 960, 540);   // turning back drops the half notch
        assertEquals(0.75, shownView.scale(), 1e-9);
        assertEquals("a.jpg — 1/3 — 3840×2160 — 75% — linview", title);
    }

    @Test
    void theNextImageForgetsTheDrag() {
        controller.setZoomMode(Zoom.Mode.ACTUAL);
        controller.open(folder);
        lastLoad().finish(1200, 1800);
        controller.pan(0, -300);
        controller.next();
        lastLoad().finish(1200, 1800);
        assertEquals(new Zoom.View(1, 360, 0), shownView);
    }
}
