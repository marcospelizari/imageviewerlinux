# linview

A fast image viewer for Linux, inspired by FastStone Image Viewer.

**Status:** early development. It opens and shows one image; moving to the next image comes next.

## What works now
- Open an image or a folder from the command line, or with Ctrl+O. Opening an image makes its folder the list.
- JPEG, PNG, GIF and BMP, centred and fitted to the window (small images are not enlarged)
- Window title shows the file name, its position in the folder and the image size
- The window opens on the monitor under the mouse pointer; Enter toggles full-screen

### Known issues
- Full-screen doesn't work yet on the second monitor of a dual-monitor setup.

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
| Ctrl+O or O | Open an image |
| Enter | Full-screen on/off |
| Esc | Leave full-screen; quit from a window |

## License
GPL-3.0 — see [LICENSE](LICENSE).

linview is an independent project and is not affiliated with FastStone Soft.
