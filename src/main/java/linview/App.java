package linview;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
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
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
