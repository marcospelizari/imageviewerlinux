# linview

A fast image viewer for Linux, inspired by FastStone Image Viewer.

**Status:** early development. It opens a folder, moves through its images and zooms, with zoom lock; panning comes next.

## What works now
- Open an image or a folder from the command line, or with Ctrl+O. Opening an image makes its folder the list.
- Move through the folder with the keyboard; it stops at the first and last image
- JPEG, PNG, GIF and BMP
- Zoom modes: fit, fit width and actual size (fit height only through the settings file for now); the mode is remembered. An image larger than the window opens at its top (top-centre if also wider); fit only shrinks, so an image smaller than the window keeps its size
- + / − zoom the image on screen; the next image opens in the zoom mode again
- Zoom lock (Ctrl+Shift+L): every next image opens at the same zoom %, at its top; + / − change the locked %. Remembered after a restart
- Photos are turned upright using their EXIF orientation
- Window title shows the file name, its position in the folder, the image size and the zoom %
- The window opens on the monitor under the mouse pointer; Enter toggles full-screen

## Roadmap
| Version | Contents |
|---|---|
| 0.1 | Core viewer: open a file or folder, natural sort, next/previous, zoom modes and zoom lock, formats JPEG, PNG, GIF, BMP, WebP, TIFF, PSD, TGA, EXIF rotation, settings file |
| 0.2 | Speed: background loading at screen size, preloading, memory-limited cache |
| 0.3 | Full-screen: thumbnail strip on top, toolbar at the bottom, file name overlay, magnifier |
| 1.0 | Delete to Trash, copy/move to favourite folders, FastStone-style shortcuts, "Open with" in file managers, installer |

Later versions may add camera RAW/HEIC, a browser mode, editing and batch tools.

## Build and run
Requires Linux and Java 21. The Gradle wrapper downloads everything else.

```sh
./gradlew run                                   # start the app
./gradlew run --args="$HOME/Pictures/photo.jpg"  # open an image (and its folder)
./gradlew test                                  # run the tests
```

Use an absolute path with `--args`: Gradle runs the app from the project folder.

On KDE Plasma (Wayland), turn on *System Settings → Window Management → Window Behavior → Multiscreen behaviour → Active screen follows mouse*,
so new windows open on the monitor with the mouse pointer.

## Keys
| Key | Action |
|---|---|
| → PgDn Space | Next image |
| ← PgUp Backspace | Previous image |
| Home / End | First / last image |
| Ctrl+O or O | Open an image |
| Enter, F11 or double-click | Full-screen on/off |
| Esc | Leave full-screen; quit from a window |
| + / − | Zoom in / out (keypad too) |
| B | Fit to window |
| W | Fit width |
| A | Fit to window ↔ actual size (100%) |
| 1 | Actual size (100%) |
| Ctrl+Shift+L | Zoom lock on/off |

## Settings
Settings are kept in `~/.config/linview/settings.properties` (or `$XDG_CONFIG_HOME/linview/`).
The file is optional: missing keys use the default, and an invalid value uses the default and logs a warning.
Edit it while linview is closed. Most settings are used by features that are still being built.

| Key | Values | Default |
|---|---|---|
| `zoom.mode` | `fit`, `width`, `height`, `actual`, `lock` | `fit` |
| `zoom.percent` | zoom % used by `lock`, 1–200 | `100` |
| `wheel` | `scroll` (wheel scrolls the image) / `navigate` (next/previous image) | `scroll` |
| `background` | `black`, `darkgrey`, `grey`, `white` or `#RRGGBB` | `black` |
| `overlay.fileName` | `true` / `false`: file name in full-screen | `true` |
| `start.fullScreen` | `true` / `false`: start in full-screen (last-used mode) | `false` |
| `window.x`, `window.y` | window position; empty = the monitor under the mouse pointer | empty |
| `window.width`, `window.height` | window size, at least 200 | `1024`, `700` |

## License
GPL-3.0 — see [LICENSE](LICENSE).

linview is an independent project and is not affiliated with FastStone Soft.
