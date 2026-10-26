package linview.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

/** The shortcut list (#36), F1 or the right-click menu: {@link Keys#LIST}, closed with Close, Enter or Esc. */
final class ShortcutsWindow {

    private ShortcutsWindow() {
    }

    static void show(Window owner) {
        GridPane grid = new GridPane();
        grid.setHgap(24);
        grid.setVgap(6);
        grid.setPadding(new Insets(16));
        int row = 0;
        for (Keys.Shortcut shortcut : Keys.LIST) {
            Label keys = new Label(shortcut.keys());
            keys.setStyle("-fx-font-weight: bold;");
            grid.addRow(row++, keys, new Label(shortcut.action()));
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Keyboard shortcuts");
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }
}
