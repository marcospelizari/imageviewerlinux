package linview;

import java.util.List;

import javafx.application.Application;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.robot.Robot;
import javafx.stage.Screen;
import javafx.stage.Stage;

/** Entry point. Phase 1: an empty window with full-screen toggle (Enter) and quit (Esc). */
public class App extends Application {

    static final String TITLE = "linview";

    @Override
    public void start(Stage stage) {
        StackPane root = new StackPane();
        root.setStyle("-fx-background-color: black;");

        Scene scene = new Scene(root, 1024, 700);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                stage.setFullScreen(!stage.isFullScreen());
            } else if (e.getCode() == KeyCode.ESCAPE && !stage.isFullScreen()) {
                stage.close();
            }
        });

        stage.setFullScreenExitHint("");
        stage.setTitle(TITLE);
        stage.setScene(scene);
        placeOnMouseScreen(stage, scene.getWidth(), scene.getHeight());
        stage.show();
    }

    /** Without an explicit position JavaFX centres on the primary monitor; use the mouse's monitor instead. */
    private static void placeOnMouseScreen(Stage stage, double width, double height) {
        List<Rectangle2D> screens = Screen.getScreens().stream().map(Screen::getVisualBounds).toList();
        Rectangle2D screen = WindowPlacement.screenAt(
                new Robot().getMousePosition(), screens, Screen.getPrimary().getVisualBounds());
        Point2D corner = WindowPlacement.centredOn(screen, width, height);
        stage.setX(corner.getX());
        stage.setY(corner.getY());
    }

    public static void main(String[] args) {
        launch(args);
    }
}
