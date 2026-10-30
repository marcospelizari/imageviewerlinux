# linview

A fast image viewer for Linux, inspired by FastStone Image Viewer.

**Status:** 1.0.0, the first release for daily use. It opens a folder, moves through its images quickly (preloading and a memory-limited cache), zooms (also at the mouse pointer) with zoom lock, and drags large images. Full-screen has a thumbnail strip at the top, a taskbar at the bottom-left, a position overlay and a magnifier. Del moves an image to the Trash, a turn can be saved to the file, and F12 opens the settings.

## What works now
- Open an image or a folder from the command line, or with Ctrl+O. Opening an image makes its folder the list.
- Move through the folder with the keyboard; it stops at the first and last image
- JPEG, PNG, GIF (animated) and BMP; WebP, TIFF, PSD and TGA (first page of a multi-page TIFF, first frame of an animated WebP)
- Zoom modes: fit, fit width and actual size (fit height only through the settings file for now); the mode is remembered. An image larger than the window opens at its top (top-centre if also wider); fit only shrinks, so an image smaller than the window keeps its size
- + / − zoom the image on screen; the next image opens in the zoom mode again
- Mouse wheel scrolls a large image, or goes to the next/previous image with `wheel=navigate` in the settings file
- Ctrl + mouse wheel zooms at the mouse pointer; drag with the left button to move an image larger than the window (grab-hand cursor). When the image fits, hold the left button for a magnifier at twice actual size. Resizing the window or switching full-screen keeps your place
- Zoom lock (Ctrl+Shift+L, or Lock in the full-screen taskbar): every next image opens at the same zoom %, at its top; + / − change the locked %. The lock stays on after closing; each open fits its first image and locks that %
- Photos are turned upright using their EXIF orientation
- Images shown smaller than their size are redrawn sharp (Lanczos) a moment after zooming stops
- Actual size (100 %) is one image pixel per screen pixel at any desktop scaling (e.g. 200 % on a 4K monitor)
- The next images are loaded in the background and recently seen ones kept (up to 300 MB), so they show at once. Very large images are decoded only as large as needed; one too large for memory shows a message
- R / L turn the image on screen 90° right / left. When you leave a turned image, linview asks whether to save the turn to the file (or always / never, see `rotation.save`): a JPEG gets only a new EXIF orientation, its image data untouched; a PNG or BMP is written again without loss (a PNG keeps its colour profile and text). The file's modified date stays. Other formats keep the turn in the view only
- Del moves the image to the Trash after asking (Enter = move, Esc = cancel), then shows the next one. Uses the desktop's `gio trash`, so files on a USB drive go to that drive's Trash and your file manager shows them; if the Trash can't take a file, it stays where it is
- Window title shows the file name, its position in the folder, the image size and the zoom %
- The window opens on the monitor under the mouse pointer, as it was closed (full-screen, or the same size and place); Enter toggles full-screen, where the mouse pointer hides after 2 s without movement
- In full-screen, moving the mouse to the top edge shows a thumbnail strip of the folder; click a thumbnail to open it. Thumbnails are shared with your file manager (`~/.cache/thumbnails`)
- In full-screen, the bottom edge at the left shows a small taskbar: previous / next, − / zoom % / +, Fit / 100% / Lock, and the image size; type a % and press Enter. A small `2/16` in a corner shows where you are in the folder

## Roadmap
| Version | Contents |
|---|---|
| 0.1 | Core viewer: open a file or folder, natural sort, next/previous, zoom modes and zoom lock, formats JPEG, PNG, GIF, BMP, WebP, TIFF, PSD, TGA, EXIF rotation, settings file |
| 0.2 | Speed: background loading at screen size, preloading, memory-limited cache |
| 0.3 | Full-screen: thumbnail strip on top, toolbar at the bottom, file name overlay, magnifier |
| 1.0 | Delete to Trash, save a turn to the file, settings window, right-click menu, FastStone-style shortcuts, "Open with" in file managers, installer |
| 1.x | Copy/move to favourite folders, faster very large images |

Later versions may add camera RAW/HEIC, a browser mode, editing and batch tools.

## Build and run
Requires Linux and Java 21. The Gradle wrapper downloads everything else. Moving to the Trash uses `gio` (GLib's
tools, part of most desktops; on Debian/Ubuntu the package `libglib2.0-bin`).

```sh
./gradlew run                                   # start the app
./gradlew run --args="$HOME/Pictures/photo.jpg"  # open an image (and its folder)
./gradlew test                                  # run the tests
```

Use an absolute path with `--args`: Gradle runs the app from the project folder.

To run linview without Gradle or an installed Java, build the packaged app (about 100 MB, with its own Java runtime
and JavaFX) and start it from `build/jpackage/linview/bin/`:

```sh
./gradlew appImage
build/jpackage/linview/bin/linview "$HOME/Pictures/photo.jpg"
```

(`./gradlew installDist` does not give a working start script: use `appImage`.)

### Install for your user
No root and nothing outside your home folder, so it also suits image-based systems such as Bazzite or Silverblue.
Linux on x86-64 only; nothing else is needed, the app brings its own Java runtime.

From a release, download `linview-<version>-linux-x64.tar.gz`, then:

```sh
tar xzf linview-1.0.0-linux-x64.tar.gz
linview-1.0.0/install.sh               # or again with a newer release to update
linview-1.0.0/install.sh --uninstall   # removes it
```

From the source code, the same with Gradle:

```sh
./gradlew installLocal     # or again to update
./gradlew uninstallLocal   # removes it
./gradlew releaseArchive   # builds the release archive in build/release/
```

This puts the app in `~/.local/share/linview`, adds linview to the app menu with its icon, and offers it in the file
manager's "Open with" for JPEG, PNG, GIF, BMP, WebP, TIFF, PSD and TGA. As usual on Linux, it doesn't take over
double-click: choose the default yourself in the file manager (right-click → Open With → Other Application… →
"Remember application association") or in System Settings → Default Applications, or for all its formats at once:

```sh
for type in image/jpeg image/png image/gif image/bmp image/webp image/tiff image/vnd.adobe.photoshop image/x-tga; do
    xdg-mime default linview.desktop "$type"
done
```

To measure speed, add `--timing`: each image writes a line like `timing  photo.jpg  shown 120.0 ms  load 100.0 ms`
to the terminal (`shown` = from the key press until the image is on its way to the screen, `load` = decoding),
and closing the app prints the median, the time 9 of 10 images were shown within, and the peak Java heap.

```sh
./gradlew run --args="--timing $HOME/Pictures"
```

On KDE Plasma (Wayland), turn on *System Settings → Window Management → Window Behavior → Multiscreen behaviour → Active screen follows mouse*,
so new windows open on the monitor with the mouse pointer.

## Keys
Press F1 in linview (or right-click → Keyboard shortcuts…) to see this list. The keys copy FastStone Image Viewer.

| Key | Action |
|---|---|
| → / ← | Next / previous image; on an image wider than the window they scroll it instead (stop at its edges) |
| ↓ / ↑ | Scroll an image taller than the window |
| PgDn Space / PgUp Backspace | Next / previous image, always |
| Home / End | First / last image |
| Ctrl+O or O | Open an image or a folder |
| Enter or F11 | Full-screen on/off |
| Esc | Quit, also from full-screen (the next start is in full-screen then) |
| + / − | Zoom in / out (keypad too) |
| Mouse wheel | Scroll the image, or next/previous image (`wheel` setting) |
| Ctrl or Shift + mouse wheel | Zoom in / out at the mouse pointer |
| Drag (left button) | Move an image larger than the window |
| Click (left button) | Immediately: right half next image; left half previous image (each click) |
| Hold left button | Magnifier (twice actual size) when the image fits the window |
| W | Fit width |
| A | Fit to window ↔ actual size (100%) |
| 1 / 2 | Actual size (100%) / 200% (keypad too) |
| Ctrl+Shift+L | Zoom lock on/off |
| R / L | Rotate the view 90° right / left (saving to the file is asked when you leave the image) |
| Del | Move to the Trash (asks first) |
| F12 | Settings window (also the Settings button in the full-screen taskbar) |
| F1 | Keyboard shortcuts (this list) |
| Right-click | Menu with the main actions and their keys: open, zoom modes (fit height too), rotate, full-screen, Trash, settings, shortcuts |

## Settings
Press F12 (or Settings in the full-screen taskbar) for the settings window: how images open (fit, fit width, fit
height, actual size or zoom lock), the mouse wheel, the background colour, the full-screen overlay and saving turned
images. OK applies the changes at once and saves them; Cancel or Esc drops them.

Settings are kept in `~/.config/linview/settings.properties` (or `$XDG_CONFIG_HOME/linview/`).
The file is optional: missing keys use the default, and an invalid value uses the default and logs a warning.
It can still be edited by hand while linview is closed.

| Key | Values | Default |
|---|---|---|
| `zoom.mode` | `fit`, `width`, `height`, `actual`, `lock` | `fit` |
| `zoom.percent` | zoom % used by `lock`, 1–200 | `100` |
| `wheel` | `scroll` (wheel scrolls the image, stops at its edges) / `navigate` (wheel down = next image, up = previous) | `scroll` |
| `background` | `black`, `darkgrey` (#333333), `grey` (#808080), `white` or `#RRGGBB`: around the image and behind its transparent parts | `black` |
| `overlay.counter` | `true` / `false`: position `2/16` in the full-screen overlay | `true` |
| `overlay.fileName` | `true` / `false`: file name in the full-screen overlay | `false` |
| `overlay.corner` | `bottom-left`, `bottom-right`, `top-left`, `top-right`: where the overlay goes | `bottom-left` |
| `rotation.save` | `ask` / `always` / `never`: save a turn made with R / L to the file when leaving the image ("Don't ask again" sets it) | `ask` |
| `start.fullScreen` | `true` / `false`: start in full-screen; saved on closing | `false` |
| `window.x`, `window.y` | window position, saved on closing; used when the mouse is on that monitor at start, else the window is centred on the mouse's monitor | empty |
| `window.width`, `window.height` | window size, at least 200; saved on closing | `1024`, `700` |

## License
GPL-3.0 — see [LICENSE](LICENSE).

linview is an independent project and is not affiliated with FastStone Soft.
