package linview.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    private String fullImageForLoupe;
    private Zoom.View shownView;
    private Orientation shownOrientation;
    private String message;
    private List<Path> stripFiles = List.of();
    private int stripIndex = -1;
    private String zoomShown = "";
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
    /** What "Save rotation?" answers (#51), the names asked about, the notes and the saves requested. */
    private ViewerController.RotationAnswer rotationAnswer = new ViewerController.RotationAnswer(true, false);
    private final List<String> askedToSave = new ArrayList<>();
    private final List<String> notSavable = new ArrayList<>();
    private final List<String> saves = new ArrayList<>();
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
        return newController(background, Runnable::run);
    }

    /** {@code onJavaFXThread} hands listing results back to the controller. */
    private ViewerController<String> newController(Executor background, Executor onJavaFXThread) {
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
                    public void showFullImage(String image) {
                        fullImageForLoupe = image;
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

                    @Override
                    public void showPosition(List<Path> files, int index) {
                        stripFiles = files;
                        stripIndex = index;
                    }

                    @Override
                    public void showZoom(double width, double height, double percent, boolean locked) {
                        zoomShown = width > 0 ? String.format("%.0f×%.0f %s%%%s", width, height, percent,
                                locked ? " locked" : "") : "";
                    }

                    @Override
                    public ViewerController.RotationAnswer askSaveRotation(String name) {
                        askedToSave.add(name);
                        return rotationAnswer;
                    }

                    @Override
                    public void showRotationNotSavable(String name) {
                        notSavable.add(name);
                    }

                    @Override
                    public void saveRotation(Path file, Orientation stored, double width, double height,
                                             int quarterTurns) {
                        saves.add(String.format("%s %d° %s %.0f×%.0f +%d", file.getFileName(), stored.degrees(),
                                stored.mirror() ? "mirrored" : "", width, height, quarterTurns));
                    }
                }, new TimingLog(timingLines::add, () -> now), background, onJavaFXThread);
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

    /** #28: the strip gets the folder and the selected image; a thumbnail click selects, and preloads follow it. */
    @Test
    void theStripFollowsTheSelectionAndSelectsAnImage() {
        controller.open(folder);
        List<Path> files = stripFiles;
        assertEquals(List.of("a.jpg", "b.jpg", "c.jpg"),
                files.stream().map(f -> f.getFileName().toString()).toList());
        assertEquals(0, stripIndex);

        controller.select(2);
        assertEquals(2, stripIndex);
        assertSame(files, stripFiles);   // same folder, same list: the strip needn't rebuild
        assertEquals("c.jpg — 3/3 — linview", title);
        assertEquals(List.of("b.jpg"), preloads.get(1));   // moved forward to the end: one behind only

        controller.select(1);
        assertEquals(List.of("a.jpg", "c.jpg"), preloads.get(2));   // moved back: a ahead, c behind
        int loads = this.loads.size();
        controller.select(1);
        assertEquals(loads, this.loads.size());   // already shown: nothing happens
    }

    /** Preloads ask for what the zoom mode needs (#21); the loader caps huge images (#55). */
    @Test
    void preloadsAskForWhatTheZoomModeNeeds() {
        controller.open(folder);
        assertEquals(0, preloadResolution);

        settings.setZoomPercent(25);
        controller.setZoomMode(Zoom.Mode.LOCK);
        controller.next();

        assertEquals(0.25, preloadResolution);
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

    /** #55: a listing for an earlier open that hasn't started when its turn comes is skipped, not run. */
    @Test
    void aSupersededListingIsSkipped() throws IOException {
        List<Runnable> listings = new ArrayList<>();
        List<Runnable> results = new ArrayList<>();
        controller = newController(listings::add, results::add);
        controller.resize(1920, 1080);
        Path other = Files.createDirectory(folder.resolve("other"));
        Files.createFile(other.resolve("x.jpg"));

        controller.open(folder);
        controller.open(other);
        listings.get(0).run();

        assertEquals(List.of(), results);   // nothing listed, nothing handed back
        listings.get(1).run();
        results.forEach(Runnable::run);
        assertEquals(List.of("x.jpg"), loads.stream().map(l -> l.file().getFileName().toString()).toList());
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

    /** #29: a typed % is like + / −: only this image; the next one starts from the mode again. */
    @Test
    void aTypedPercentZoomsOnlyTheImageOnScreen() {
        controller.open(folder);
        lastLoad().finish(800, 600);
        assertEquals("800×600 100.0%", zoomShown);   // fit never enlarges

        controller.zoomToPercent(52.5);
        assertEquals(0.525, shownView.scale(), 1e-9);
        assertEquals("800×600 52.5%", zoomShown);

        controller.next();
        lastLoad().finish(800, 600);
        assertEquals(1, shownView.scale(), 1e-9);
    }

    /** #29: with zoom lock on, a typed % changes the locked %, which is saved. */
    @Test
    void aTypedPercentChangesTheLockedZoom() throws IOException {
        controller.open(folder);
        lastLoad().finish(800, 600);
        controller.toggleZoomLock();
        controller.zoomToPercent(40);
        assertEquals("800×600 40.0% locked", zoomShown);

        controller.next();
        lastLoad().finish(800, 600);
        assertEquals(0.4, shownView.scale(), 1e-9);
        store.flush();
        assertTrue(Files.readString(settingsFile).contains("zoom.percent=40"));
    }

    @Test
    void theTaskbarIsEmptyWhenNothingIsOpen() throws IOException {
        controller.open(folder);
        lastLoad().finish(800, 600);
        controller.open(folder.resolve("missing"));
        assertEquals("", zoomShown);
    }

    /** #45: the overlay's text; the file name is optional. */
    @Test
    void theOverlayShowsTheCounterAndOptionallyTheName() {
        assertEquals("2/16", ViewerController.overlayText("a.jpg", 2, 16, true, false));
        assertEquals("a.jpg  2/16", ViewerController.overlayText("a.jpg", 2, 16, true, true));
        assertEquals("a.jpg", ViewerController.overlayText("a.jpg", 2, 16, false, true));
        assertEquals("", ViewerController.overlayText("a.jpg", 2, 16, false, false));
    }

    /** #32: the magnifier asks for the full image of a screen-size copy, once; a whole image needs nothing. */
    @Test
    void theMagnifierLoadsTheFullImageOfACopy() {
        controller.open(folder);
        lastLoad().finish(3840, 2160, 0.5);
        controller.loadFullResolution();
        assertTrue(lastLoad().full());
        assertEquals(2, loads.size());
        controller.loadFullResolution();
        assertEquals(2, loads.size());

        controller.next();
        lastLoad().finish(800, 600);
        controller.loadFullResolution();
        assertEquals(3, loads.size());
    }

    /**
     * #60: the full image asked for by the magnifier goes to the magnifier only; the view keeps the copy until a zoom
     * needs more, then swaps it in without loading again.
     */
    @Test
    void theMagnifiersFullImageStaysOutOfTheViewUntilAZoomNeedsIt() {
        controller.open(folder);
        lastLoad().finish(3840, 2160, 0.5);   // fit: 50%, the copy is enough
        controller.loadFullResolution();
        lastLoad().finish(3840, 2160, 1);
        assertEquals("a.jpg full", fullImageForLoupe);
        assertEquals("a.jpg copy", shownImage);

        controller.setZoomMode(Zoom.Mode.ACTUAL);
        assertEquals("a.jpg full", shownImage);
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

    /** #65: the file was replaced before its full image loaded: laid out with the new size and EXIF, R / L kept. */
    @Test
    void aFullImageOfAReplacedFileIsLaidOutAsNew() {
        controller.open(folder);
        lastLoad().finish(10000, 5000, 0.2);
        controller.setZoomMode(Zoom.Mode.ACTUAL);
        Pending full = lastLoad();
        controller.rotateRight();

        full.done().accept(new Loaded<>("a.jpg new", 3000, 4000, new Orientation(90, false)));

        assertEquals("a.jpg new", shownImage);
        assertEquals(new Orientation(180, false), shownOrientation);   // the new EXIF, turned once more
        assertTrue(title.contains("3000×4000 — 100%"), title);
        assertEquals(2, loads.size());   // the new full image is used, not loaded again
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

        assertEquals(List.of("timing  a.jpg  shown 120.0 ms  load 100.0 ms  cache no"), timingLines);
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

        assertEquals(List.of("timing  b.jpg  shown 30.0 ms  load 0.0 ms  cache no"), timingLines);
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

    /** #20: an image that needs too much memory says so, not "damaged". */
    @Test
    void anImageTooLargeToLoadSaysSo() {
        controller.open(folder);
        lastLoad().done().accept(Loaded.tooLarge(29437, 10751));
        assertEquals("Can't show a.jpg: the image is too large (316 MP)", message);
        controller.next();
        lastLoad().finish(800, 600);
        assertEquals("b.jpg", shownImage);
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

    /** #49: the menu's image items are on only while the selected image is on screen. */
    @Test
    void imageShownOnlyOnceTheSelectedImageHasLoaded() {
        assertFalse(controller.isImageShown());
        controller.open(folder);
        assertFalse(controller.isImageShown());
        lastLoad().finish(800, 600);
        assertTrue(controller.isImageShown());
        controller.next();
        assertFalse(controller.isImageShown());   // a still on screen, b selected (R7)
        lastLoad().done().accept(Loaded.failed());
        assertFalse(controller.isImageShown());
    }

    /** #34, review R7: Del has a target only once the selected image has finished loading, shown or failed. */
    @Test
    void trashTargetWaitsForTheSelectedImage() {
        assertNull(controller.trashTarget());
        controller.open(folder);
        assertNull(controller.trashTarget());
        lastLoad().finish(800, 600);
        assertEquals(folder.resolve("a.jpg"), controller.trashTarget());
        controller.next();
        assertNull(controller.trashTarget());   // a still on screen, b selected
        lastLoad().done().accept(Loaded.failed());
        assertEquals(folder.resolve("b.jpg"), controller.trashTarget());   // a damaged file can go too
    }

    @Test
    void trashedCurrentImageShowsTheNextOne() {
        controller.open(folder);
        lastLoad().finish(800, 600);
        controller.trashed(folder.resolve("a.jpg"));
        assertEquals(List.of(folder.resolve("b.jpg"), folder.resolve("c.jpg")), stripFiles);
        assertEquals(0, stripIndex);
        assertEquals(folder.resolve("b.jpg"), lastLoad().file());
        lastLoad().finish(800, 600);
        assertEquals("b.jpg", shownImage);
        assertTrue(title.startsWith("b.jpg — 1/2"), title);
    }

    @Test
    void trashedLastImageShowsThePreviousOne() {
        controller.open(folder);
        lastLoad().finish(800, 600);
        controller.last();
        lastLoad().finish(800, 600);
        controller.trashed(folder.resolve("c.jpg"));
        assertEquals(folder.resolve("b.jpg"), lastLoad().file());
        assertEquals(1, stripIndex);
    }

    @Test
    void trashedOnlyImageLeavesAMessage() throws IOException {
        Path single = Files.createDirectory(folder.resolve("single"));
        Files.createFile(single.resolve("x.jpg"));
        controller.open(single);
        lastLoad().finish(800, 600);
        controller.trashed(single.resolve("x.jpg"));
        assertEquals("No images left in " + single + "\n\n" + ViewerController.OPEN_HINT, message);
        assertEquals(List.of(), stripFiles);
        assertNull(controller.trashTarget());
    }

    /** Moved on while the Trash was busy: the image on screen and its zoom stay, only n/N changes. */
    @Test
    void trashedEarlierImageKeepsTheOneShown() {
        controller.open(folder);
        lastLoad().finish(800, 600);
        controller.next();
        lastLoad().finish(800, 600);
        int loadsBefore = loads.size();
        controller.trashed(folder.resolve("a.jpg"));
        assertEquals(loadsBefore, loads.size());
        assertEquals("b.jpg", shownImage);
        assertEquals(0, stripIndex);
        assertTrue(title.startsWith("b.jpg — 1/2 — 800×600"), title);
    }

    @Test
    void trashedFileOfAnotherFolderChangesNothing() {
        controller.open(folder);
        lastLoad().finish(800, 600);
        controller.trashed(folder.resolve("elsewhere/a.jpg"));
        assertEquals(3, stripFiles.size());
        assertEquals("a.jpg", shownImage);
    }

    /** #51: leaving a turned image asks, and saves the turn on top of the orientation it was shown with. */
    @Test
    void leavingATurnedImageAsksAndSaves() {
        controller.open(folder);
        lastLoad().done().accept(new Loaded<>("a.jpg", 800, 600, new Orientation(90, true)));
        controller.rotateRight();
        controller.rotateRight();
        controller.rotateRight();   // 3 × right = 1 × left
        controller.next();
        assertEquals(List.of("a.jpg"), askedToSave);
        assertEquals(List.of("a.jpg 90° mirrored 800×600 +3"), saves);
        lastLoad().finish(800, 600);
        controller.previous();   // b was not turned
        assertEquals(1, askedToSave.size());
    }

    @Test
    void aFullTurnOrNoTurnAsksNothing() {
        controller.open(folder);
        lastLoad().finish(800, 600);
        for (int i = 0; i < 4; i++) {
            controller.rotateLeft();
        }
        controller.next();
        lastLoad().finish(800, 600);
        controller.next();
        assertEquals(List.of(), askedToSave);
        assertEquals(List.of(), saves);
    }

    @Test
    void dontSaveAndDontAskAgainNeverSaves() throws IOException {
        rotationAnswer = new ViewerController.RotationAnswer(false, true);
        controller.open(folder);
        lastLoad().finish(800, 600);
        controller.rotateRight();
        controller.next();
        assertEquals(Settings.RotationSave.NEVER, settings.rotationSave());
        lastLoad().finish(800, 600);
        controller.rotateRight();
        controller.next();
        assertEquals(1, askedToSave.size());
        assertEquals(List.of(), saves);
        store.flush();
        assertTrue(Files.readString(settingsFile).contains("rotation.save=never"));
    }

    @Test
    void saveAndDontAskAgainAlwaysSaves() {
        rotationAnswer = new ViewerController.RotationAnswer(true, true);
        controller.open(folder);
        lastLoad().finish(800, 600);
        controller.rotateRight();
        controller.next();
        lastLoad().finish(800, 600);
        controller.rotateRight();
        controller.next();
        assertEquals(Settings.RotationSave.ALWAYS, settings.rotationSave());
        assertEquals(1, askedToSave.size());
        assertEquals(2, saves.size());
    }

    /** Opening another folder and quitting leave the image too. */
    @Test
    void openingAndQuittingLeaveTheImage() {
        controller.open(folder);
        lastLoad().finish(800, 600);
        controller.rotateRight();
        controller.open(folder.resolve("b.jpg"));
        assertEquals(List.of("a.jpg"), askedToSave);
        lastLoad().finish(800, 600);
        controller.rotateRight();
        controller.leaveImage();   // the window quits
        controller.leaveImage();   // asked once only
        assertEquals(List.of("a.jpg", "b.jpg"), askedToSave);
    }

    @Test
    void formatsThatCantKeepATurnGetANoteOncePerRun() throws IOException {
        Files.createFile(folder.resolve("d.gif"));
        Files.createFile(folder.resolve("e.webp"));
        controller.open(folder.resolve("d.gif"));
        lastLoad().finish(800, 600);
        controller.rotateRight();
        controller.next();
        lastLoad().finish(800, 600);
        controller.rotateRight();
        controller.next();
        assertEquals(List.of("d.gif"), notSavable);
        assertEquals(List.of(), askedToSave);
    }

    /** Back on the image before its save ended: it loads again, from the saved file. */
    @Test
    void rotationSavedReloadsTheImageOnScreen() {
        controller.open(folder);
        lastLoad().finish(800, 600);
        int loadsBefore = loads.size();
        controller.rotationSaved(folder.resolve("b.jpg"));   // not on screen
        assertEquals(loadsBefore, loads.size());
        controller.rotationSaved(folder.resolve("a.jpg"));
        assertEquals(loadsBefore + 1, loads.size());
        assertEquals(folder.resolve("a.jpg"), lastLoad().file());
    }

    /** #26: closing remembers the mode and the window; a window smaller than allowed is saved at the minimum. */
    @Test
    void closingRemembersTheModeAndTheWindow() throws IOException {
        controller.windowClosed(true, 1920 + 200, 100.5, 1400, 150);
        store.flush();

        String saved = Files.readString(settingsFile);
        assertTrue(saved.contains("start.fullScreen=true"), saved);
        assertTrue(saved.contains("window.x=2120"), saved);
        assertTrue(saved.contains("window.y=100.5"), saved);
        assertTrue(saved.contains("window.width=1400"), saved);
        assertTrue(saved.contains("window.height=200"), saved);
        assertTrue(warnings.isEmpty(), warnings.toString());
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

    /** #48: Lock chosen in the settings window locks the image on screen, like Ctrl+Shift+L. */
    @Test
    void lockFromTheSettingsWindowLocksTheImageOnScreen() {
        controller.open(folder);
        lastLoad().finish(3840, 2160);   // fit: 50%
        controller.chooseZoomMode(Zoom.Mode.LOCK);
        assertEquals(Zoom.Mode.LOCK, settings.zoomMode());
        assertEquals(50, settings.zoomPercent());
        assertTrue(title.contains("50% (locked)"), title);
        controller.chooseZoomMode(Zoom.Mode.LOCK);   // already locked: unchanged
        assertEquals(Zoom.Mode.LOCK, settings.zoomMode());
        controller.chooseZoomMode(Zoom.Mode.HEIGHT);
        assertEquals(Zoom.Mode.HEIGHT, settings.zoomMode());
        assertEquals(1080.0 / 2160, shownView.scale(), 1e-9);
    }

    /** With nothing on screen, Lock waits for the next open, which fits its first image and locks that (#63). */
    @Test
    void lockFromTheSettingsWindowWithNothingOpen() throws IOException {
        controller.chooseZoomMode(Zoom.Mode.LOCK);
        assertEquals(Zoom.Mode.LOCK, settings.zoomMode());
        store.flush();
        assertTrue(Files.readString(settingsFile).contains("zoom.mode=lock"));
        controller.open(folder);
        lastLoad().finish(3840, 2160);
        assertEquals(50, settings.zoomPercent());
    }

    /** #63: closed while locked, the next start fits the first image and locks its %; the next images keep it. */
    @Test
    void aLockFromBeforeFitsTheFirstImageAndLocksItsPercent() throws IOException {
        settings.setZoomMode(Zoom.Mode.LOCK);   // as loaded from the settings file
        settings.setZoomPercent(70);
        controller.open(folder);
        assertEquals(0, lastLoad().minResolution());   // asked as for fit, not 70%
        lastLoad().finish(3840, 2160);
        assertEquals(Zoom.Mode.LOCK, settings.zoomMode());
        assertEquals(0.5, shownView.scale(), 1e-9);
        assertTrue(title.contains("50% (locked)"), title);

        controller.next();
        lastLoad().finish(1200, 1800);   // fit would be 60%: the lock keeps 50%
        assertEquals(0.5, shownView.scale(), 1e-9);
        store.flush();
        assertTrue(Files.readString(settingsFile).contains("zoom.mode=lock\nzoom.percent=50\n"));
    }

    /** #63: until the next image, the first one follows the window, e.g. going full-screen at the start. */
    @Test
    void theFirstLockedImageFollowsAResize() {
        settings.setZoomMode(Zoom.Mode.LOCK);
        controller.open(folder);
        lastLoad().finish(3840, 2160);
        controller.resize(960, 540);
        assertEquals(0.25, shownView.scale(), 1e-9);
        assertEquals(25, settings.zoomPercent());

        controller.next();
        lastLoad().finish(3840, 2160);
        controller.resize(1920, 1080);   // a later image keeps the locked %
        assertEquals(0.25, shownView.scale(), 1e-9);
        assertEquals(25, settings.zoomPercent());
    }

    /** #63: each open, by Ctrl+O too, keeps the lock and takes its % from the first image's fit. */
    @Test
    void openingAgainKeepsTheLock() {
        controller.open(folder);
        lastLoad().finish(3840, 2160);
        controller.zoomOut();
        controller.toggleZoomLock();
        assertEquals(Zoom.Mode.LOCK, settings.zoomMode());

        controller.open(folder.resolve("b.jpg"));
        lastLoad().finish(1920, 2160);

        assertEquals(Zoom.Mode.LOCK, settings.zoomMode());
        assertEquals(0.5, shownView.scale(), 1e-9);   // fit height
        assertEquals(50, settings.zoomPercent());
        assertTrue(title.contains("50% (locked)"), title);
        controller.toggleZoomLock();   // still turns it off
        assertEquals(Zoom.Mode.FIT, settings.zoomMode());
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

    /** #36, FastStone: where the image fits, ← → go to the previous / next image and ↑ ↓ do nothing. */
    @Test
    void arrowsNavigateWhenTheImageFits() {
        controller.open(folder);
        lastLoad().finish(800, 600);
        Zoom.View before = shownView;
        controller.arrow(0, 1);
        controller.arrow(0, -1);
        assertEquals(before, shownView);
        controller.arrow(1, 0);
        assertEquals("b.jpg — 2/3 — linview", title);
        controller.arrow(-1, 0);
        assertEquals("a.jpg — 1/3 — linview", title);
    }

    /** #36: an image wider than the window scrolls by a wheel notch and stops at its edges, never changing image. */
    @Test
    void arrowsScrollALargerImageAndStopAtTheEdges() {
        controller.setZoomMode(Zoom.Mode.ACTUAL);
        controller.open(folder);
        lastLoad().finish(3000, 2000);   // wider and taller than 1920×1080: opens at the top-centre
        assertEquals(new Zoom.View(1, -540, 0), shownView);
        int count = loads.size();
        controller.arrow(1, 0);
        controller.arrow(0, 1);
        assertEquals(new Zoom.View(1, -648, -108), shownView);
        for (int i = 0; i < 20; i++) {
            controller.arrow(1, 0);
            controller.arrow(0, 1);
        }
        assertEquals(new Zoom.View(1, 1920 - 3000, 1080 - 2000), shownView);
        controller.arrow(-1, 0);
        assertEquals(new Zoom.View(1, 1920 - 3000 + 108, 1080 - 2000), shownView);
        assertEquals(count, loads.size());
    }

    /** #36: fit width only scrolls down; ← → still navigate, despite rounding in the scale. */
    @Test
    void arrowsInFitWidthScrollDownAndNavigateSideways() {
        controller.setZoomMode(Zoom.Mode.WIDTH);
        controller.open(folder);
        lastLoad().finish(1234, 4321);
        controller.arrow(0, 1);
        assertEquals(-108, shownView.y(), 1e-9);
        controller.arrow(1, 0);
        assertEquals("b.jpg — 2/3 — linview", title);
    }

    /** #36: while the next image loads, the old one on screen doesn't stop ← → from navigating. */
    @Test
    void arrowsKeepNavigatingWhileTheNextImageLoads() {
        controller.setZoomMode(Zoom.Mode.ACTUAL);
        controller.open(folder);
        lastLoad().finish(3000, 2000);
        controller.next();               // b.jpg loading, a.jpg still on screen
        controller.arrow(1, 0);
        assertEquals("c.jpg — 3/3 — linview", title);
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
