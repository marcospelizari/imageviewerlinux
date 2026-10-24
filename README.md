# linview

A fast image viewer for Linux, inspired by FastStone Image Viewer.

**Status:** early development (0.2.0). It opens a folder, moves through its images quickly (preloading and a memory-limited cache), zooms (also at the mouse pointer) with zoom lock, and drags large images.

## What works now
- Open an image or a folder from the command line, or with Ctrl+O. Opening an image makes its folder the list.
- Move through the folder with the keyboard; it stops at the first and last image
- JPEG, PNG, GIF (animated) and BMP; WebP, TIFF, PSD and TGA (first page of a multi-page TIFF, first frame of an animated WebP)
- Zoom modes: fit, fit width and actual size (fit height only through the settings file for now); the mode is remembered. An image larger than the window opens at its top (top-centre if also wider); fit only shrinks, so an image smaller than the window keeps its size
- + / − zoom the image on screen; the next image opens in the zoom mode again
- Mouse wheel scrolls a large image, or goes to the next/previous image with `wheel=navigate` in the settings file
- Ctrl + mouse wheel zooms at the mouse pointer; drag with the left button to move an image larger than the window (grab-hand cursor). Resizing the window or switching full-screen keeps your place
- Zoom lock (Ctrl+Shift+L, or Lock in the full-screen taskbar): every next image opens at the same zoom %, at its top; + / − change the locked %. Each open starts unlocked, in the zoom mode before the lock: open a photo, see it fit, then lock that %
- Photos are turned upright using their EXIF orientation
- Images shown smaller than their size are redrawn sharp (Lanczos) a moment after zooming stops
- Actual size (100 %) is one image pixel per screen pixel at any desktop scaling (e.g. 200 % on a 4K monitor)
- The next images are loaded in the background and recently seen ones kept (up to 300 MB), so they show at once. Very large images are decoded only as large as needed; one too large for memory shows a message
- R / L turn the image on screen 90° right / left; the file is not changed, and the next image opens upright again
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

To run linview without Gradle or an installed Java, build the packaged app (about 100 MB, with its own Java runtime
and JavaFX) and start it from `build/jpackage/linview/bin/`:

```sh
./gradlew appImage
build/jpackage/linview/bin/linview "$HOME/Pictures/photo.jpg"
```

An installer and "Open with linview" in file managers come later. (`./gradlew installDist` does not give a working
start script: use `appImage`.)

To measure speed, add `--timing`: each image writes a line like `timing  photo.jpg  shown 120.0 ms  load 100.0 ms`
to the terminal (`shown` = from the key press until the image is on its way to the screen, `load` = decoding),
and closing the app prints the median, the time 9 of 10 images were shown within, and the peak Java heap.

```sh
./gradlew run --args="--timing $HOME/Pictures"
```

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
| Esc | Quit, also from full-screen (the next start is in full-screen then) |
| + / − | Zoom in / out (keypad too) |
| Mouse wheel | Scroll the image, or next/previous image (`wheel` setting) |
| Ctrl + mouse wheel | Zoom in / out at the mouse pointer |
| Drag (left button) | Move an image larger than the window |
| B | Fit to window |
| W | Fit width |
| A | Fit to window ↔ actual size (100%) |
| 1 | Actual size (100%) |
| Ctrl+Shift+L | Zoom lock on/off |
| R / L | Rotate the view 90° right / left (the file is not changed) |

## Settings
Settings are kept in `~/.config/linview/settings.properties` (or `$XDG_CONFIG_HOME/linview/`).
The file is optional: missing keys use the default, and an invalid value uses the default and logs a warning.
Edit it while linview is closed. Most settings are used by features that are still being built.

| Key | Values | Default |
|---|---|---|
| `zoom.mode` | `fit`, `width`, `height`, `actual`, `lock` | `fit` |
| `zoom.percent` | zoom % used by `lock`, 1–200 | `100` |
| `wheel` | `scroll` (wheel scrolls the image, stops at its edges) / `navigate` (wheel down = next image, up = previous) | `scroll` |
| `background` | `black`, `darkgrey` (#333333), `grey` (#808080), `white` or `#RRGGBB`: around the image and behind its transparent parts | `black` |
| `overlay.counter` | `true` / `false`: position `2/16` in the full-screen overlay | `true` |
| `overlay.fileName` | `true` / `false`: file name in the full-screen overlay | `false` |
| `overlay.corner` | `bottom-left`, `bottom-right`, `top-left`, `top-right`: where the overlay goes | `bottom-left` |
| `start.fullScreen` | `true` / `false`: start in full-screen; saved on closing | `false` |
| `window.x`, `window.y` | window position, saved on closing; used when the mouse is on that monitor at start, else the window is centred on the mouse's monitor | empty |
| `window.width`, `window.height` | window size, at least 200; saved on closing | `1024`, `700` |

## License
GPL-3.0 — see [LICENSE](LICENSE).

linview is an independent project and is not affiliated with FastStone Soft.
