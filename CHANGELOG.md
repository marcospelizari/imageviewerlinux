# Changelog

All notable changes are recorded here. Versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]
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
