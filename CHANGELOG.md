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

### Changed
- README describes what works now, the roadmap, keys and known issues (#41)
- New application icon (camera), made from `assets/icon/linview_icon.png`; it replaces the first one (#40)

## [0.0.1] - Phase 1
### Added
- Gradle project (Java 21, JavaFX 21), Gradle wrapper
- Empty window: Enter toggles full-screen, Esc quits
- JUnit 5 test setup
- GitLab CI pipeline (build + test)
- GPL-3.0 license
