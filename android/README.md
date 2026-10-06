# Vacation Map for Android

The native Android version of the Vacation Map web app in this repository: Kotlin and Jetpack Compose, no WebView. It is the web app's mobile layout (design direction 1a in [design_handoff_mobile_app](../design_handoff_mobile_app/README.md)) screen for screen — Map, Places, Trip, Settings, destination detail, the add/edit form with pick-on-map, the trip sheets and the trip planner — and it reads and writes the same data, so a phone and a browser can share one map through a sync code.

## Build and run

**Android Studio:** open the `android/` folder, let it sync, pick a device or emulator and press Run.

**Command line:**

```bash
cd android
./gradlew :app:assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug           # onto a connected device or running emulator
```

This needs a JDK (17 or newer) and the Android SDK with platform 37. On Debian/Ubuntu, such as the repo's devcontainer, `scripts/setup-toolchain.sh` installs both. The devcontainer cannot run an emulator (it has no `/dev/kvm`), so install the APK on a phone, or run it from Android Studio on the host.

Requires Android 10 (API 29) or newer.

## Configuration

| What | Where | Default |
|---|---|---|
| Gemini API key (trip planner suggestions, research, Translate) | `gemini.apiKey=` in `android/local.properties`, or the `GEMINI_API_KEY` environment variable, or `VITE_GEMINI_API_KEY` in the repo's `.env` | none: the AI features say they are unavailable |
| Cloud sync backend (`/api/data`, see the root README) | `-Pvacationmap.apiBaseUrl=https://…` on the Gradle command line, or in `gradle.properties` | `https://road-trippin-six.vercel.app` |

Both are baked into the app at build time. As in the web build, the Gemini key can be read out of the built app, so don't ship a key you wouldn't put on a web page. If the backend has no database (or no `/api/data` at all), the app notices and keeps its data on the device only.

## How it stays in step with the web app

The web app is the source of truth for everything the two share. After changing it, regenerate from the repo root:

| Shared thing | Source | Regenerate with |
|---|---|---|
| UI strings, English and Greek | `src/i18n/translations.ts` | `node android/scripts/gen-i18n.mjs` |
| Seed data | `public/data/vacation-data.json` | nothing: bundled straight from there |
| Expected results of the shared logic | `src/services/*.ts`, `src/store/mapDataStore.ts` | `node android/scripts/gen-parity-fixtures.mjs` |
| Icons (Material Symbols Rounded, only the ones used) | `android/scripts/icons.txt` | `android/scripts/fetch-icon-font.sh` |

The date maths, plan editing, day planner, suggestion ranking and data migration are ported to Kotlin in `logic/`. `ParityTest` replays about 1,750 recorded inputs from the TypeScript originals through the Kotlin port and expects identical results, so if the web logic changes and the Kotlin doesn't, regenerating the fixtures makes the test say where.

## Tests

```bash
cd android
./gradlew :app:testDebugUnitTest            # everything; no device needed
./gradlew :app:testDebugUnitTest -PrealTiles # screenshots over real OpenStreetMap tiles (needs network)
```

- `ParityTest` — the Kotlin logic against the web app's, as above.
- `SourceChecksTest` — every string key and icon name the UI uses exists.
- `AppScreenshotTest`, `MapScreenshotTest` — render the real screens with Robolectric and write PNGs to `app/build/outputs/roborazzi/` to look at. They are pictures to review, not comparisons against golden images.
- `FlowTest` — the trip planner from dates to a saved trip, pick-on-map town names and the image export, against canned Gemini/OSRM/Wikimedia/Photon answers.
- `PlaceSearchTest` — searching a place by name in the form and taking its name and coordinates.
- `CloudSyncTest` — when this device's data goes up, when another device's comes down, and what happens offline.

The tests use no network unless asked to (`-PrealTiles` also lets place photos load). They run on the JVM. Nothing here has run on a device or emulator yet: gestures (map pan/zoom/fling, long-press drag in Trip), the photo picker, saving to Pictures and the file pickers are written against the platform APIs but untested on hardware.

## Layout

```
app/src/main/java/io/github/corum86/vacationmap/
  model/      the data model, field for field the web app's (same JSON)
  logic/      pure logic ported from src/services: dates, plan, planner, ranking, migration
  data/       the store (edits), cloud sync, storage, device files
  net/        OSRM, Photon, Wikimedia, Gemini and /api/data clients
  map/        the map: tiles, camera, gestures, and everything drawn on top
  i18n/       generated string tables and the translator
  ui/         theme, components, screens, the trip planner, the shell
```

The map is drawn by the app itself rather than by a map SDK: OpenStreetMap raster tiles on a Compose canvas, with the arrows, routes, pins and labels drawn in the same pass (as the web app overlays them on Leaflet). That keeps the overlay glued to the map, lets the same code render the exported image off screen at any size, and makes the map testable without a device. Tiles are cached on disk and requests carry an identifying User-Agent, as the OSM tile usage policy asks.

## Differences from the web app

- Phone layout only. On tablets the phone layout stretches; the web's desktop layout (rail, side panel, floating detail) is not ported.
- "Export map" saves the PNG to Pictures/Vacation Map instead of opening a share sheet.
- Data import and export go through the system file picker.
- The first launch follows the device language if it is Greek; after that the in-app switch decides.
