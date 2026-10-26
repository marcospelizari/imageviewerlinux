package linview.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;

import linview.settings.Settings;

/** #48: the colour picker's value as the settings file keeps it, and accepted by the setter. */
class SettingsWindowTest {

    @Test
    void colourAsHex() {
        assertEquals("#000000", SettingsWindow.hex(Color.BLACK));
        assertEquals("#333333", SettingsWindow.hex(Color.web("#333333")));
        assertEquals("#1a2b3c", SettingsWindow.hex(Color.web("#1A2B3C")));
        Settings settings = new Settings();
        settings.setBackground(SettingsWindow.hex(Color.web("#808080")));
        assertEquals("#808080", settings.backgroundColour());
    }
}
