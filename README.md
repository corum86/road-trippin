# Vacation Map

A vacation-planning map app: one main location (home base) connected to your destinations by curvy arrows on an OpenStreetMap map. Click a destination to see route distance and driving time, main attractions, photos, and links. Everything is editable in the app, and the map can be exported as a PNG image.

# Demo
https://road-trippin-six.vercel.app/

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
- **Display modes** — a toolbar button group switches between curved arrows, actual road routes (OSRM geometry, fetched once per destination and cached; dashed straight line while loading or when routing is unavailable), and points only.
- **Destination details** — click a destination (marker or sidebar list) to see:
  - Route length and driving time, fetched once per destination from the free [OSRM demo server](https://router.project-osrm.org) and cached. If the request fails, a straight-line estimate is shown instead and clearly labeled.
  - Main attractions, notes, a photo gallery (image URLs, click to enlarge), and links.
- **Editing** — add/edit/delete destinations and edit the main location entirely in the UI, including "📍 Pick on map": while picking, the cities and towns in view (villages too once zoomed in) appear as selectable name pills — choosing one fills in the name and its coordinates; clicking any other spot sets just the coordinates.
- **Persistence** — data seeds from [public/data/vacation-data.json](public/data/vacation-data.json) on first load and is kept in your browser's localStorage, so the app opens instantly and works offline. With a database configured (see [Cloud sync](#cloud-sync-mongodb)), every change is also saved to MongoDB and can be shared between devices; without one, edits stay in the browser only. *Export data* downloads a JSON backup; to make your edits the new defaults, replace `public/data/vacation-data.json` with the exported file. *Import data* loads a previously exported JSON; *Reset* clears the app — it deletes every place, the trip and the home base (after asking), keeping only the language. The seed file is not touched by a reset: it stays as the first-load data and as a fixture for testing, and can be brought back any time with *Import data*.
- **Aspect ratio & orientation** — toolbar icon buttons constrain the map to 16:9, 4:3, or 1:1 in landscape or portrait (or free-fill). The map letterboxes to that shape, so image exports come out in exactly the chosen ratio.
- **Export map as image** — downloads the current map view (tiles, arrows, markers, attribution) as a 2x-resolution PNG.

## Cloud sync (MongoDB)

The browser never talks to MongoDB directly: [api/data.ts](api/data.ts) holds the connection string and stores each map as one document in the `maps` collection of the `vacation_map` database (override the name with `MONGODB_DB`).

- **Local** — put the connection string in `MONGODB_URI`, either in `.env` or by keeping the `atlas-credentials.env` file from the Atlas onboarding in the project root (both are git-ignored). `npm run dev` and `npm run preview` then serve `/api/data` themselves.
- **Vercel** — add `MONGODB_URI` under *Project Settings → Environment Variables* and redeploy; `api/data.ts` is deployed as a Function automatically. Atlas must accept connections from Vercel (*Network Access* → allow `0.0.0.0/0`, or use the Atlas–Vercel integration).
- **No database** — `/api/data` answers 501, the app notices and keeps working from localStorage alone.

Each browser gets its own random **sync code** (shown under *Settings → Cloud sync*) naming its document. Paste one device's code into another to make them share a map; there are no accounts, so anyone who has a code can read and edit that map. The map is saved as a whole and the last save wins: a device picks up changes from the others when it is opened or refocused, and uploads its own a second after each edit (or once it is back online).

## Project layout

```
public/data/vacation-data.json   seed data (replace with an exported file to change defaults)
src/types/models.ts              data model (MainLocation, Destination, RouteInfo, …)
src/store/mapDataStore.ts        zustand store + localStorage persistence
src/store/cloudSync.ts           keeps the store and the MongoDB copy in step
src/services/cloudDataService.ts client for /api/data
api/data.ts                      Vercel Function: reads/writes the map in MongoDB
src/services/osrmService.ts      OSRM routing fetch + straight-line fallback
src/services/photonService.ts    city/town names around the map view (Photon geocoder), cached
src/services/geo.ts              haversine distance, bezier control-point math
src/components/map/              MapView, markers, CurvedArrowsOverlay
src/components/panels/           detail panel, edit forms, photo gallery, links
src/components/controls/         image export, JSON import/export, destination list
```

## Notes

- The OSM tile layer sets `crossOrigin="anonymous"` — required so the PNG export can rasterize tiles without tainting the canvas. Keep it if you change tile providers.
- The OSRM public demo server is free and unauthenticated; routes are fetched only when a destination is first opened and then cached in the data (including through export/import), to keep usage minimal.
- The town names offered while picking a location come from the free [Photon](https://photon.komoot.io) geocoder (OpenStreetMap data). It is only queried while picking, answers are cached for the session, and it returns at most 50 places per request, nearest the map centre first — zoom in to see more. Greek names are the local OSM names; Photon itself only translates to English.
- OSM attribution stays visible in exported images, as required by the [OSM tile usage policy](https://operations.osmfoundation.org/policies/tiles/).
