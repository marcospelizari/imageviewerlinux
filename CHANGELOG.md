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
- Zoom calculations for fit, fit width, fit height, actual size and zoom lock, with the start position (top, or top-centre when larger than the window; centred when smaller) and "enlarge small images". Logic only; the viewer uses it from #11 (#10)
- Zoom modes in the viewer: B fit, W fit width, 1 actual size, A switches between fit and actual size; the chosen mode is saved. Fit height has no key (settings file only for now). + / − zoom in steps around the middle of the window; the next image opens in the mode again. Resizing the window refits. The title shows the zoom % (#11)

### Fixed
- Full-screen on the second monitor: confirmed working since the window opens on the mouse's monitor (#2)

### Changed
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
