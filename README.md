# linview

A fast image viewer for Linux, inspired by FastStone Image Viewer.

Status: early development (Phase 1 — project setup). Not usable yet.

## Goals for 1.0
- Instant next/previous image (preloading and caching)
- Full-screen mode with hover panels (thumbnail strip on top, toolbar at the bottom)
- Zoom modes: fit, fit width, fit height, actual size, and zoom lock (keep the same % between images, starting at the top)
- JPEG, PNG, GIF, BMP, WebP, TIFF, PSD, TGA

## Build and run
Requires Java 21. The Gradle wrapper downloads everything else.

```sh
./gradlew run          # start the app
./gradlew test         # run the tests
```

## License
GPL-3.0 — see [LICENSE](LICENSE).

linview is an independent project and is not affiliated with FastStone Soft.
