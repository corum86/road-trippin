# Vacation Map

A vacation-planning map app: one main location (home base) connected to your destinations by curvy arrows on an OpenStreetMap map. Click a destination to see route distance and driving time, main attractions, photos, and links. Everything is editable in the app, and the map can be exported as a PNG image.

## Run it

```powershell
npm install
npm run dev
```

Open http://localhost:5173.

Production build: `npm run build`, then `npm run preview` to serve the static `dist/` output.

## Features

- **Map** — Leaflet + react-leaflet with OpenStreetMap tiles. The green star is the main location; red pins are destinations.
- **Curvy arrows** — decorative bezier curves drawn from the main location to each destination in an SVG overlay that stays anchored through pan and zoom. The selected destination's arrow is highlighted blue.
- **Destination details** — click a destination (marker or sidebar list) to see:
  - Route length and driving time, fetched once per destination from the free [OSRM demo server](https://router.project-osrm.org) and cached. If the request fails, a straight-line estimate is shown instead and clearly labeled.
  - Main attractions, notes, a photo gallery (image URLs, click to enlarge), and links.
- **Editing** — add/edit/delete destinations and edit the main location entirely in the UI, including "📍 Pick on map" to set coordinates by clicking the map.
- **Persistence** — data seeds from [public/data/vacation-data.json](public/data/vacation-data.json) on first load, then lives in your browser's localStorage. **Edits are saved in the browser only** — use *Export data* to download a JSON backup. To make your edits the new defaults, replace `public/data/vacation-data.json` with the exported file. *Import data* loads a previously exported JSON; *Reset* restores the bundled defaults.
- **Aspect ratio & orientation** — toolbar icon buttons constrain the map to 16:9, 4:3, or 1:1 in landscape or portrait (or free-fill). The map letterboxes to that shape, so image exports come out in exactly the chosen ratio.
- **Export map as image** — downloads the current map view (tiles, arrows, markers, attribution) as a 2x-resolution PNG.

## Project layout

```
public/data/vacation-data.json   seed data (replace with an exported file to change defaults)
src/types/models.ts              data model (MainLocation, Destination, RouteInfo, …)
src/store/mapDataStore.ts        zustand store + localStorage persistence
src/services/osrmService.ts      OSRM routing fetch + straight-line fallback
src/services/geo.ts              haversine distance, bezier control-point math
src/components/map/              MapView, markers, CurvedArrowsOverlay
src/components/panels/           detail panel, edit forms, photo gallery, links
src/components/controls/         image export, JSON import/export, destination list
```

## Notes

- The OSM tile layer sets `crossOrigin="anonymous"` — required so the PNG export can rasterize tiles without tainting the canvas. Keep it if you change tile providers.
- The OSRM public demo server is free and unauthenticated; routes are fetched only when a destination is first opened and then cached in the data (including through export/import), to keep usage minimal.
- OSM attribution stays visible in exported images, as required by the [OSM tile usage policy](https://operations.osmfoundation.org/policies/tiles/).
