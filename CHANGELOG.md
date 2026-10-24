# Changelog

All notable changes are recorded here. Versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]
### Added
- Magnifier: while the whole image fits the window, hold the left button for a round loupe at twice actual size under the mouse; move to look around, release to close it. Real pixels, unsmoothed; for a very large image the full image is loaded for it. When the image is larger than the window, the left button pans as before (Marcos's decision). In a window and in full-screen (#32)
- Full-screen taskbar at the bottom-left: move the mouse to the bottom edge at the left and it slides up over the image, only as wide as its contents: ◀ ▶ (previous / next), −, the zoom %, +, Fit and 100% (like B and 1), Lock (like Ctrl+Shift+L, highlighted while zoom lock is on), and the image size. Type a zoom % (`52`, `52%` or `52.5`, 1–200) and press Enter: it works like + / −; anything else keeps the zoom, and Esc in the box cancels the typing. The viewer's keys don't fire while you type there. It stays open while you type. A Settings button comes with the settings window (#29)
- Full-screen overlay: a small outlined `2/16` (image shown / images in the folder) in a corner, hidden while the taskbar is open. Settings `overlay.counter` (on), `overlay.fileName` (now off by default) and `overlay.corner` (`bottom-left`, `bottom-right`, `top-left`, `top-right`); no key, settings file only (#45)
- linview starts the way it was closed: in full-screen if it was closed in full-screen, else as a window of the same size. The monitor under the mouse wins: on the monitor the window was closed on it comes back at its exact place, on another one it is centred there. A place on a monitor that is no longer connected is never used, and a window larger than the screen shrinks to fit (Marcos's decision, R10). Saved in `start.fullScreen` and `window.*` when the window closes. Esc now quits at once, also from full-screen, which the next start remembers; Enter, F11 or a double-click leave full-screen (Marcos's decision). Known limit: started from a Wayland app such as Konsole, JavaFX may get an old mouse position (XWayland), so the window can open on the other monitor (#26)
- Full-screen thumbnail strip at the top: rest the mouse at the top edge for 200 ms and it slides in over the image (the image doesn't move); it slides back 300 ms after the mouse leaves. The selected image is highlighted and scrolled into view; the wheel scrolls the strip; a click opens an image. Thumbnails, 120 points tall, come from the freedesktop cache (`~/.cache/thumbnails/large`, shared with Dolphin and other file managers) or are made on a background thread of their own, turned upright, and stored there. Only the thumbnails in view are asked for, so a large folder opens at once. Images that would need more than 300 MB to decode get no thumbnail. The strip never takes the keyboard: ← → keep moving through the images. In a window there is no strip (#27, #28)
- Background colour from the settings file (`background`): around the image and behind its transparent parts, in a window and in full-screen. Presets `black` (default), `darkgrey` (#333333), `grey` (#808080), `white`, or `#RRGGBB`. Messages turn dark on a light background (#31)
- In full-screen the mouse pointer hides after 2 s without movement and comes back when the mouse moves (#33)

### Changed
- Zoom lock is per open, as in FastStone: opening an image or folder (at start or with Ctrl+O) leaves zoom lock and shows it in the mode before the lock (fit by default), so you open a photo, see it fit, and lock that %. Before, the locked % was remembered across restarts and the first photo opened at an old % (Marcos's decision, #59)
- The window code moved from `App` to `ui.ViewerWindow`: Stage and Scene, the Open dialog, full-screen and what the controller shows. Every action goes through its one entry point `execute(Keys.Action)`, which the taskbar, panels and menu will call too. `App` only builds, connects, starts and stops. No change in behaviour (#53)

## [0.2.0] - 2026-10-24 - Phase 3
### Added
- Sharp shrinking: when an image is shown smaller than its pixels (fit on most photos, zoom below 100 %), it is redrawn with Lanczos-3 in the background 150 ms after the last change and swapped in, one pixel per screen pixel, instead of the graphics card's grainy scaling. The first view still appears at once, zooming never waits, and dragging keeps the sharp copy. Our own resampler spread over the CPU cores (≈ 100 ms for 7.2 MP → 1080p; TwelveMonkeys' `ResampleOp` took 1–2 s). Not for animated GIFs or images decoded over 40 MP (#24)
- Any desktop scaling: zoom works in physical screen pixels, so actual size (100 %) shows one image pixel per screen pixel also at 150–200 % scaling (4K monitors), instead of 2×2 and blurred. The image's corner is placed on a whole screen pixel. Fit modes, the zoom % in the title, the wheel, drags and Ctrl + wheel use the same pixels. Moving the window to a monitor with other scaling counts as a resize. Text keeps following the desktop scaling (#46)
- Image cache with a 300 MB budget (decoded pixels, W×H×4): images you've seen stay after they leave the preload window, so going back is instant, until the room is needed. The least recently used goes first, images still to come only after all others, the one on screen never. Preloading pauses while the budget is full. This replaces #21's rule of keeping only the preload window. The timing log says `cache yes/no` for each image. Memory a decode needs is estimated before it starts: an image needing more than 300 MB (about 100 MP as a JPEG) isn't preloaded, and these large decodes run one at a time, so huge images can't run out of memory together. Normal photos never wait behind them. One needing more than 1.2 GB, or that runs out of memory anyway, shows "the image is too large (N MP)" instead of "damaged" (#20)
- Preloading: after each image, the next 3 in the direction you move and 1 behind are loaded in the background, one at a time, so they show at once. The image you selected always goes first: a preload never runs while it loads, one running is cancelled, and an image already being preloaded is taken over instead of loaded twice. Only these images are kept; a file changed or deleted since is loaded again. Opening a folder lists and sorts it in the background; a folder opened earlier that finishes listing later is ignored (#21)
- Very large images (over 40 MP) are decoded only as large as the screen needs, then in full when zooming past that, e.g. actual size or + / −. A 229 MP JPEG opens in fit in about 2 s instead of 3.4 s, with a 23 MB Java heap instead of 663 MB. Normal photos load whole, as before. Actual size and zoom lock ask for the pixels they need at once, so no image is decoded twice. The EXIF orientation and the image size are read off the UI thread (#19, #22)
- Timing log, off by default: `--timing` on the command line writes one line per image to standard error (`shown` = from the key press until the image is handed to the window, `load` = the loader's own time, or `failed after`), and on closing, also with Ctrl+C, a summary: the median, the time 9 of 10 images were shown within, and the peak of each Java heap pool (#23)
- `./gradlew appImage` builds a packaged app in `build/jpackage/linview` (jpackage app image, about 100 MB) with its own Java runtime and JavaFX: it starts without Gradle or an installed Java, also with spaces and accents in the path. No installer or `.desktop` file yet (#54)

### Fixed
- A preload of a very large image could restart endlessly: rounding pushed it just over the 40 MP limit, it wasn't kept, and the same preload began again, using CPU and memory and stopping the preloads after it. A capped preload now really stays within 40 MP, and one that still can't be kept isn't tried again while it stays in the window. At most two folder listings run at once, and only the newest waiting open is kept, so folders that hang can't pile up threads. When JavaFX can't load an image, the reason is now written to the terminal (e.g. out of memory), not only "damaged" on screen; a deliberately cancelled load isn't logged (#57)
- Selecting an image that was already preloaded no longer reads the disk on the UI thread: the check that its file is unchanged runs in the background, so a slow USB disk or network folder can't freeze the window. A folder that is slow to list no longer holds up one opened after it, and a listing no longer wanted is skipped (#55)
- JPEGs never showed in the packaged app on systems without `libjpeg.so.8` (e.g. Fedora/Bazzite; since #19): the image size is now read with metadata-extractor in the same pass as the EXIF orientation, instead of Java's native JPEG reader. A load that fails unexpectedly now shows the usual "Can't show" message and is logged, instead of staying "loading" forever (#21)

### Changed
- Java heap limit raised from 1.5 GB to 2 GB (`-Xmx2g`), so very large JPEGs fit: JavaFX decodes them at full size even for a smaller copy, about 950 MB for a 317 MP image (Marcos's decision; #57)
- Preloads ask for what the zoom mode needs, but a very large neighbour (over 40 MP) is decoded to at most 40 MP: zoom lock at 25 % still gets huge images preloaded at 25 %, while actual size no longer preloads them in full, which could run out of memory; they are decoded in full when selected. Nothing decoded over 40 MP is kept. A preload that is still wanted keeps running when another image is selected instead of being cancelled: JavaFX can't stop a decode it has started, so cancelling only wasted it and decoded the image again later (#55)
- Settings are saved in the background, never on the UI thread: quick changes in a row (Ctrl + wheel with zoom lock on) end in one write of the newest values, and closing the app writes a change still pending. The file is still replaced only after the new one is fully written. Settings are read before the window opens (#52)
- Version 0.1.0 in `build.gradle`, matching the `v0.1.0` tag (#54); 0.2.0 for this release

## [0.1.0] - 2026-10-24 - Phase 2
### Added
- The window opens on the monitor under the mouse pointer (#25)
- Application icon in the window and taskbar, PNG 16–512 px (#40)
- Natural sort of a folder's images: `img2` before `img10`, case-insensitive, hidden files skipped (#3)
- Open an image or a folder from the command line or with Ctrl+O / O; the folder becomes the list. Messages for a missing file, an empty folder, an unsupported or damaged file (#4)
- Shows JPEG, PNG, GIF and BMP, centred, fitted to the window without enlarging small images (#4, #5)
- Window title: `name — n/N — W×H — linview` (#4)
- Next/previous image with → PgDn Space / ← PgUp Backspace, first/last with Home/End; stops at the ends of the folder. All shortcuts are in `Keys.java` (#6)
- Photos are shown upright using their EXIF orientation (all 8 values); no or broken EXIF → shown as stored. New dependency: metadata-extractor (Apache-2.0) (#8)
- F11 and a double-click toggle full-screen too, besides Enter (#18)
- Settings file `~/.config/linview/settings.properties`, read at start: missing file → defaults, invalid value → default + warning in the log. Saving replaces the file only after the new one is fully written; the features that change a setting will use it. Keys listed in the README (#7)
- Zoom calculations for fit, fit width, fit height, actual size and zoom lock, with the start position (top, or top-centre when larger than the window; centred when smaller). Logic only; the viewer uses it from #11 (#10)
- Zoom lock with Ctrl+Shift+L (FastStone's key): locks the current zoom %, so next/previous images open at that % and at their top (top-centre if also wider); + / − change the locked %; pressed again it returns to the previous mode. Saved in the settings; the title shows "(locked)" (#12)
- Zoom modes in the viewer: B fit, W fit width, 1 actual size, A switches between fit and actual size; the chosen mode is saved. Fit height has no key (settings file only for now). + / − zoom in steps around the middle of the window; the next image opens in the mode again. Resizing the window refits. The title shows the zoom % (#11)
- Ctrl + mouse wheel zooms one step per notch at the mouse pointer, which stays over the same spot of the image; same steps and rules as + / −, a touchpad adds up small movements. Drag with the left button to move an image larger than the window, never past its edges; grab-hand cursor while there is something to drag. After a zoom or a drag, resizing the window or switching full-screen keeps the middle of the view in place. A drag no longer counts as a double-click (#14)
- Mouse wheel follows the `wheel` setting: `scroll` (default) scrolls the image, 10% of the window height per notch, sideways too with a tilt wheel or touchpad, and stops at the image's edges; `navigate` goes to the next (down) or previous (up) image. A touchpad's small movements add up to whole notches, also for Ctrl + wheel zoom. Settings file only, no switch in the app (#15)
- R / L rotate the image on screen 90° right / left, on top of its EXIF orientation; the file is not changed. The zoom starts again from the mode (a + / − zoom is dropped, zoom lock keeps its %), the title shows the turned size, and the next image opens as its EXIF says. While the next image is still loading R / L do nothing (#17)
- WebP, TIFF, PSD and TGA, decoded in the background with the TwelveMonkeys ImageIO plug-ins (BSD-3); a multi-page TIFF shows its first page, an animated WebP its first frame. JPEG, PNG, GIF and BMP still use JavaFX's own loader, so animated GIFs keep playing. The Ctrl+O filter lists the new formats. New dependencies: TwelveMonkeys `imageio-webp`, `imageio-tiff`, `imageio-psd`, `imageio-tga` 3.15.3 (#9)

### Fixed
- − no longer enlarges an image already shown below 1% (a fit of a very wide image), and + never shrinks one above 200%; with `wheel=navigate`, a part notch of Ctrl + wheel no longer adds up with the plain wheel into a change of image, or the other way round (#47)
- Full-screen on the second monitor: confirmed working since the window opens on the mouse's monitor (#2)

### Changed
- Code organised into packages `viewer`, `ui`, `image`, `settings`; `ViewerController` now runs the viewing workflow (open, navigate, load, zoom) and `App` only builds and connects the parts; `SettingsStore` reads and saves the settings file. No change in behaviour or in the settings file (#42)
- Zoom goes up to 200% at most (+ steps and `zoom.percent`); more shows too little of a small image. A saved `zoom.percent` above 200 now falls back to 100 with a warning (#42, decided by Marcos 2026-10-22)
- Fit modes only shrink: an image smaller than the window keeps its size. The `zoom.enlargeSmall` setting is removed; an old settings file with it still loads (#42, #13 dropped)
- CI: a newer push cancels a superseded pipeline; the HTML test report is kept for a week; the Gradle download is checked against its official SHA-256 (#43)
- README describes what works now, the roadmap, keys and known issues (#41)
- New application icon (camera), made from `assets/icon/linview_icon.png`; it replaces the first one (#40)

## [0.0.1] - Phase 1
### Added
- Gradle project (Java 21, JavaFX 21), Gradle wrapper
- Empty window: Enter toggles full-screen, Esc quits
- JUnit 5 test setup
- GitLab CI pipeline (build + test)
- GPL-3.0 license
