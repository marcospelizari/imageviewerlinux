package linview.ui;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Window;
import javafx.util.StringConverter;

import linview.settings.Settings;
import linview.viewer.Zoom;

/**
 * The settings window (R-SET-02, #48), F12 or the taskbar's Settings button: every preference the settings file has,
 * on one page. Nothing changes until OK (Enter); Cancel or Esc drops every change (Marcos, 2026-10-26). Window place,
 * size and start mode are saved on closing and not shown; the locked % neither, as each open sets it (#63). Values go
 * through the {@link Settings} setters when applied, the one place for the rules.
 */
final class SettingsWindow {

    /** What OK applies; {@code background} is {@code null} when the colour wasn't changed, so a preset name stays. */
    record Choices(Zoom.Mode openMode, Settings.Wheel wheel, String background, boolean counter, boolean fileName,
                   Settings.Corner corner, Settings.RotationSave rotationSave) {
    }

    private SettingsWindow() {
    }

    /** Shows the window over {@code owner} with the values of {@code settings}; empty when cancelled. */
    static Optional<Choices> ask(Window owner, Settings settings) {
        ChoiceBox<Zoom.Mode> openMode = choice(List.of(Zoom.Mode.values()), settings.zoomMode(), mode -> switch (mode) {
            case FIT -> "Fit to window";
            case WIDTH -> "Fit width";
            case HEIGHT -> "Fit height";
            case ACTUAL -> "Actual size (100%)";
            case LOCK -> "Zoom lock (each open fits, then keeps that %)";
        });
        ChoiceBox<Settings.Wheel> wheel = choice(List.of(Settings.Wheel.values()), settings.wheel(),
                w -> w == Settings.Wheel.SCROLL ? "Scrolls the image" : "Goes to the next / previous image");
        Color startColour = Color.web(settings.backgroundColour());
        ColorPicker background = new ColorPicker(startColour);
        // As tall as the other boxes: at its own height the colour's name is cut off at the bottom (Marcos).
        background.setMinHeight(Region.USE_PREF_SIZE);
        background.prefHeightProperty().bind(openMode.heightProperty());
        CheckBox counter = new CheckBox("Position (2/16)");
        counter.setSelected(settings.counterOverlay());
        CheckBox fileName = new CheckBox("File name");
        fileName.setSelected(settings.fileNameOverlay());
        ChoiceBox<Settings.Corner> corner = choice(List.of(Settings.Corner.values()), settings.overlayCorner(),
                c -> switch (c) {
                    case BOTTOM_LEFT -> "Bottom left";
                    case BOTTOM_RIGHT -> "Bottom right";
                    case TOP_LEFT -> "Top left";
                    case TOP_RIGHT -> "Top right";
                });
        ChoiceBox<Settings.RotationSave> rotation = choice(List.of(Settings.RotationSave.values()),
                settings.rotationSave(), r -> switch (r) {
                    case ASK -> "Ask whether to save it to the file";
                    case ALWAYS -> "Always save it to the file";
                    case NEVER -> "Keep it in the view only";
                });

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Open images at"), openMode);
        grid.addRow(1, new Label("Mouse wheel"), wheel);
        grid.addRow(2, new Label("Background"), background);
        grid.addRow(3, new Label("Full-screen overlay"), new HBox(12, counter, fileName, corner));
        grid.addRow(4, new Label("Turned with R / L"), rotation);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Settings");
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return Optional.empty();
        }
        Color colour = background.getValue();
        return Optional.of(new Choices(openMode.getValue(), wheel.getValue(),
                colour.equals(startColour) ? null : hex(colour), counter.isSelected(), fileName.isSelected(),
                corner.getValue(), rotation.getValue()));
    }

    /** {@code #rrggbb}, as the settings file keeps a colour (R-FS-06). */
    static String hex(Color colour) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", Math.round(colour.getRed() * 255),
                Math.round(colour.getGreen() * 255), Math.round(colour.getBlue() * 255));
    }

    private static <T> ChoiceBox<T> choice(List<T> values, T selected, java.util.function.Function<T, String> label) {
        ChoiceBox<T> box = new ChoiceBox<>();
        box.getItems().setAll(values);
        box.setValue(selected);
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : label.apply(value);
            }

            @Override
            public T fromString(String text) {
                throw new UnsupportedOperationException();
            }
        });
        return box;
    }
}
