package linview;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Dimension2D;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import linview.files.FileActions;
import linview.image.loading.ImageLoader;
import linview.image.Loaded;
import linview.image.thumbnail.ThumbnailLoader;
import linview.settings.Settings;
import linview.settings.SettingsStore;
import linview.ui.ViewerWindow;
import linview.ui.WindowPlacement;
import linview.viewer.TimingLog;
import linview.viewer.ViewerController;

/**
 * Entry point and composition root (REPORT.md §6): builds the components, connects, starts and stops them.
 * Opens the file or folder given on the command line (R-NAV-01); {@code --timing} turns on the timing log (#23).
 * The window and its actions are in {@link ViewerWindow} (#53); what they do to the viewing in {@link ViewerController}.
 */
public class App extends Application {

    private static ThreadPoolExecutor folderListing() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
                Thread.ofPlatform().name("linview-folders-", 0).daemon().factory(),
                new ThreadPoolExecutor.DiscardOldestPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** Turns on the timing log (#23): one line per image on standard error, a summary on closing. */
    static final String TIMING_FLAG = "--timing";

    private static final System.Logger LOG = System.getLogger("linview");

    private ViewerController<Image> controller;
    private ImageLoader loader;
    private ThumbnailLoader thumbnails;
    /**
     * Lists folders off the JavaFX thread (#21). Two at a time, so one slow folder (USB, network) doesn't hold up the
     * next (#55); at most one more waits, and a newer open replaces it, so stalled folders can't pile up threads (#57).
     * A listing already running can't be interrupted: its result is ignored. Threads end when idle.
     */
    private final ThreadPoolExecutor folders = folderListing();
    private TimingLog timing = TimingLog.OFF;
    private SettingsStore store;
    private Settings settings;

    /** Reads the settings on JavaFX's launcher thread, before the window exists: no file I/O on the UI thread (#52). */
    @Override
    public void init() {
        store = new SettingsStore(SettingsStore.defaultFile(),
                warning -> LOG.log(System.Logger.Level.WARNING, warning));
        settings = store.load();
    }

    @Override
    public void start(Stage stage) {
        Dimension2D screen = WindowPlacement.largestScreenPixels();
        loader = new ImageLoader(screen.getWidth(), screen.getHeight());
        List<String> args = new ArrayList<>(getParameters().getRaw());
        if (args.remove(TIMING_FLAG)) {
            timing = TimingLog.toStandardError();
            // Also when the app is stopped from the terminal (Ctrl+C), which skips stop().
            Runtime.getRuntime().addShutdownHook(new Thread(timing::summary, "linview-timing-summary"));
        }
        ViewerController.ImageSource<Image> source = new ViewerController.ImageSource<>() {
            @Override
            public Runnable load(Path file, double minResolution, Consumer<Loaded<Image>> done) {
                return loader.load(file, minResolution, done);
            }

            @Override
            public void preload(List<Path> files, double minResolution) {
                loader.preload(files, minResolution);
            }
        };
        thumbnails = new ThumbnailLoader();
        ViewerWindow window = new ViewerWindow(stage, thumbnails, new FileActions());
        controller = new ViewerController<>(settings, store, source, window, timing, folders, Platform::runLater);
        window.show(controller, settings);

        if (args.isEmpty()) {
            controller.showOpenHintIfEmpty();
        } else {
            controller.open(Path.of(args.get(0)));
        }
    }

    @Override
    public void stop() {
        if (loader != null) {
            loader.close();
        }
        if (thumbnails != null) {
            thumbnails.close();
        }
        folders.shutdownNow();
        store.close();   // writes a change still pending
    }

    public static void main(String[] args) {
        launch(args);
    }
}
