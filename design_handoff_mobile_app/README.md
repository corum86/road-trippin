# Handoff: Vacation Map — Mobile (direction 1a "Material") + Desktop (2a)

## Overview
A mobile-first (Android) version of the existing **VacationMap** web app (`VacationMap/` — React 19 + Vite + react-leaflet + zustand + i18n EN/EL). It keeps the core concept — one home base connected to destinations by curved arrows on a map — and adds vacation-*tracker* features:

- Trip status per destination: **planned / visited**, plus a **favorite** flag
- **Visit log**: visited date, 1–5 star rating, notes, own photos
- **Day-by-day itinerary** (Trip tab)
- Mobile-native navigation: bottom **tab bar** (Map / Places / Trip / Settings), full-screen map with a card carousel, full-screen detail and form screens

The chosen direction is **1a** for mobile, with a matching **desktop layout (2a)** described in its own section below. Directions 1b/1c exist in the same prototype (prop `variant="b"|"c"`) for reference only — **implement 1a**.

## About the Design Files
The files in `design/` are **design references built in HTML** — a clickable prototype showing intended look and behavior, not production code. Recreate them inside the existing VacationMap codebase using its patterns: React function components, the zustand store (`src/store/mapDataStore.ts`), the i18n system (`src/i18n/translations.ts`), react-leaflet for the map, and the CSS-variable token approach in `src/index.css` / `src/App.css` (`vm-` class prefix).

Open `design/Vacation Map Mobile.dc.html` in a browser to click through (needs `support.js` and `android-frame.jsx` next to it). The interesting source is `design/VacationApp.dc.html`: the template (markup + inline styles) followed by a `<script data-dc-script>` logic class holding the state model, strings and map projection.

Suggested approach: make the current app responsive. Below about 600px wide, render the mobile shell described here instead of the desktop toolbar + sidebar (`AppShell.tsx`). Reuse `MapView`, `CurvedArrowsOverlay`, the markers, `osrmService`, `PhotoGallery`, `LinksList` and the edit-form logic.

## Fidelity
**High-fidelity** for layout, colors, typography, spacing, radii and interactions. Two exceptions:
- **The map is a stylized placeholder.** In production use the real Leaflet/OSM map, keeping the existing curved arrows and pin styling (already in the codebase).
- **Photos are striped placeholders.** Use the real `Photo.url` images (`object-fit: cover`) in the same boxes.

Sample content (attractions for some places, itinerary days, notes, ratings) is illustrative.

---

## Device / frame
- Designed at **396 × 812 CSS px** of app content (Android 412 × 892 with status bar 40px and gesture bar 24px). Fluid width: everything stretches horizontally, and nothing has a fixed width except where noted.
- Font: **Poppins** (already loaded in `index.html`), weights 400/500/600/700/800. Base 14px / 1.45.
- Icons: **Material Symbols Rounded** (Google Fonts, `opsz,wght,FILL,GRAD@20..48,400,0..1,0`). Filled state uses `font-variation-settings: 'FILL' 1`. Icon names are listed per component below.
- Monospace (captions, coordinates): `ui-monospace, Menlo, monospace`.

## Design Tokens
These evolve the existing `:root` tokens to a calmer look: the header gradient is gone and surfaces are flat.

| Token | Value | Use |
|---|---|---|
| `--bg` | `#fbf8f3` | App background, detail/form screens (was `#fffaf2`) |
| `--surface` | `#ffffff` | Cards, inputs |
| `--surface-2` | `#f6efe4` | Stat tiles, photo placeholder light stripe |
| `--surface-3` | `#f4eee5` | Nav bar, segmented-control track, round icon buttons |
| `--border` | `#efe4d2` | All 1px borders (was `#f0dcb8`) |
| `--text` | `#2b2118` | Primary text, dark toast |
| `--text-muted` | `#8a7a63` | Secondary text |
| `--text-faint` | `#c9b89e` | Chevrons, inactive heart, dashed borders |
| `--accent` | `#ef5a2a` | Coral: planned status, FAB, destructive-ish CTAs, arrows |
| `--accent-soft` | `#ff7a4d` | Home pin gradient end |
| `--accent-tint` | `#fde9e0` | Coral chip / secondary button background |
| `--accent-2` | `#0c8a83` | Teal: primary buttons, visited status, section labels, selected |
| `--accent-2-dark` | `#075e59` | Active nav icon, selected arrow casing |
| `--accent-2-tint` | `#e3f1ef` | Teal chip / selected backgrounds |
| `--accent-2-indicator` | `#cfe9e6` | Active nav-pill background |
| `--star` | `#ff9f52` | Filled star; empty star `#d9cbb5` |
| `--danger` | `#e63946` | Delete text and button, favorite heart (filled), errors |
| `--danger-tint` | `#fbe3e5` | Delete button background |
| map bg | `#eaefe9` | Placeholder only (real map uses OSM tiles) |

**Radii:** 10 (photo thumbs), 12 (inner segmented buttons, small inputs, thumbs), 14 (inputs, stop cards, list thumbs 64px), 16 (stat tiles, segmented track), 18 (list cards, carousel cards, settings cards, FAB, banner), 22 (selected-destination card), 26 (top search bar), 28 (dialog), pill = height/2 for buttons.

**Shadows:**
- `sm` `0 1px 3px rgba(120,72,20,.12)`: active segmented button
- `md` `0 4px 16px rgba(120,72,20,.14)`: floating top bar, carousel cards
- `lg` `0 8px 28px rgba(120,72,20,.18)`: selected-destination card
- FAB `0 6px 20px rgba(239,90,42,.35)`
- Hero icon buttons `0 2px 8px rgba(0,0,0,.12)`
- Toast `0 8px 24px rgba(0,0,0,.25)`

**Type scale:** 26/800 (detail title, line-height 1.15) · 24/700 (tab screen titles) · 18/700 (stat values, form title) · 17/700 (selected card name) · 16/700 (top bar title) · 15/700 (card names) · 15/600 · 14/600 (buttons) · 13 (secondary) · 12/700 uppercase +0.06em (section labels, teal) · 12 (meta) · 11/600 uppercase +0.05em (carousel status) · 11/600 (pin labels).

**Spacing:** screen horizontal padding 16 (lists/cards) or 20 (titles, detail content); gaps 4/6/8/10/12/14/16/22; detail section gap 22.

---

## App shell
Column flex, full height:
1. **Content area** (`flex:1`, `position:relative`, `overflow:hidden`). Each tab screen is `position:absolute; inset:0`. Scrolling screens use `overflow-y:auto` with hidden scrollbar and `padding-bottom:110px`.
2. **Bottom nav bar** (Material 3 style). Shown only when no detail/form/picker screen is open.

Full-screen screens (Detail, Form, Pick-on-map, Delete dialog, Toast) are absolutely positioned over the content area with z-index 20 / 30 / 40 / 50 / 60, and they hide the nav bar.

### Bottom nav bar
- Height 80, background `#f4eee5`, horizontal padding 8, 4 equal items.
- Item: column, centered, gap 4. **Indicator pill** 64×32, radius 16: active bg `#cfe9e6`, inactive transparent, `transition: background .2s`. Icon 24px: active `#075e59` FILL 1, inactive `#5c4f40` FILL 0. Label 12px: active weight 700, inactive 500, color `#2b2118`.
- Tabs (icon / EN / EL): `map` Map/Χάρτης · `pin_drop` Places/Μέρη · `luggage` Trip/Ταξίδι · `settings` Settings/Ρυθμίσεις.

---

## Screens

### 1. Map (tab)
**Purpose:** see home base + all destinations, pick one, jump to its details.

- **Map**: full-bleed under everything (396×732). Production: Leaflet map with OSM tiles. Fit bounds to home + destinations with padding: top 100px (under the bar), bottom 150px (above the carousel) or 200px when a destination is selected, sides 40px. Attribution stays visible bottom-right, just above the carousel/card.
- **Arrows**: the existing `CurvedArrowsOverlay` unchanged (coral `#ef5a2a` + casing `#a83c17`; selected teal `#0c8a83` + casing `#075e59`, drawn last). When a destination is selected, the other arrows drop to opacity 0.55 (otherwise 0.85).
- **Pins**: the existing `.vm-marker-pin-*` styles. Destination 24px coral gradient `#ff8f66→#ef5a2a`, selected 30px teal `#2dd4c4→#0c8a83`, home 34px `#ffd166→#ff7a4d` with ★. All have a 3px white border and shadow `0 2px 6px rgba(0,0,0,.35)`. **New:** visited destinations show a white `check` icon inside the pin (counter-rotated). **New:** a name label pill next to every pin: 11px/600, padding 3×8, radius 10, bg `rgba(255,255,255,.95)`, shadow `0 1px 3px rgba(0,0,0,.15)`. The label sits right of the pin, or left of it when the pin is in the right ~45% of the viewport so it never clips. Home label shows the short name (text after "— ").
- **Floating top bar**: absolute top/left/right 12, height 52, white, radius 26, shadow md, padding `0 6px 0 18px`, gap 4.
  - Title "Vacation Map" 16/700 (flex 1).
  - **Display-mode button** 40×40 round, bg `#f4eee5`, icon 21px: `conversion_path` (arrows) ↔ `scatter_plot` (points). Toggles between arrows and points-only. You can extend it to cycle the existing 3 modes (arrows / routes / points) from `RouteDisplayToggle`.
  - **Language button**: 40 high, min-width 44, radius 20, bg `#f4eee5`, 12/700, label "EN" / "ΕΛ". Toggles the language.
- **Bottom area, nothing selected — carousel**: absolute bottom 12, horizontal scroll (hidden scrollbar), padding `4px 12px`, gap 10.
  - **Destination card**: 190 wide, white, radius 18, shadow md, padding `12px 14px`, gap 2.
    - Row: 8px status dot (visited `#0c8a83`, planned `#ef5a2a`) + status label 11/600 uppercase muted.
    - Name 15/700, single line with ellipsis.
    - Route "24.9 km · 30 min" 12 muted.
    - Tap selects the destination.
  - **Add tile** at the end: 72 wide, coral `#ef5a2a`, radius 18, white `add` icon 28px. Opens Add destination.
- **Bottom area, destination selected — card**: absolute left/right/bottom 12, white, radius 22, shadow lg, padding 14, column gap 12.
  - Row: 64×64 photo (radius 14) · name 17/700 · route 13/600 teal · status 12 muted · `close` icon button 36 (clears selection).
  - Full-width **Details** button, height 46, radius 23, bg `#0c8a83`, white 14/600. Opens Detail.
- Tapping a pin selects it. Tapping empty map clears the selection.

### 2. Places (tab)
- Title "Places" 24/700, padding `20px 20px 12px`.
- **Filter chips** row: horizontal scroll, gap 8, padding `0 20px 14px`. Chip height 34, radius 17, padding 0 14, 13/600, count after the label at opacity .6.
  - Active: bg/border `#2b2118`, white text.
  - Inactive: white bg, `#efe4d2` border.
  - Filters: All · Planned · Visited · Favorites.
- **List** (gap 10, padding 0 16): card white, border, radius 18, padding 10, gap 14.
  - 68×68 photo, radius 12.
  - Name 15/700.
  - Route 12 muted.
  - Status chip 11/600, padding 2×9, radius 10. Visited: bg `#e3f1ef` text `#0c8a83`. Planned: bg `#fde9e0` text `#ef5a2a`.
  - Visited places also show "★★★★" stars text 11 muted.
  - Heart icon 22px at the right: favorite `#e63946` FILL 1, otherwise `#c9b89e` FILL 0. Tapping it toggles favorite without opening the card.
  - Tapping the card opens Detail.
- Empty filter: centered muted "Nothing here yet."
- **Extended FAB**: absolute right 16, bottom 16, height 56, radius 18, coral, white 14/600, `add` icon 24, label "Add destination", shadow FAB.

### 3. Trip (tab), new itinerary
- Title = trip name ("Epirus summer") 24/700.
- Subline "13–18 Jul 2026 · 2 of 5 visited" 13 muted.
- **Progress bar**: 6px, radius 3, track `#efe4d2`, fill `#0c8a83` = visited/total.
- **Day timeline** (padding 0 16). Each row: left column 52px with a 44px circle and a 2px `#efe4d2` connector line below it.
  - Circle shows "DAY" 9/600 uppercase over the number 16/700.
  - Circle colors: all stops visited → teal fill with white text. Has stops → white. Empty → `#f4eee5`.
  - Right column: date 12/600 muted ("Tue 14 Jul"), then stop cards (gap 8): white, border, radius 14, padding 10×12.
    - Filled icon 20px: `check` teal if visited, `location_on` coral if planned.
    - Name 14/600 and route 12 muted.
    - `chevron_right` `#c9b89e`.
    - Tapping a stop opens Detail.
  - Empty days show a note ("Arrive at home base" / "Free day") 14 muted.
- Destinations not assigned to any day appear in a final "+" / "Not scheduled" group.
- Data model needs `days: { date, stopIds[], note? }[]` (see State).

### 4. Settings (tab)
Column gap 22. Each section: label 12/700 uppercase teal +0.06em, then content (padding 0 16).
- **Home base** card: white, border, radius 18, padding 14.
  - 40px round gradient `#ffd166→#ff7a4d` with ★.
  - Name 14/600.
  - Coords mono 12 muted ("39.50778, 20.26306").
  - `edit` icon.
  - Tapping the card opens the Edit main location form.
- **Language**: segmented control, track `#f4eee5` radius 16 padding 4 gap 4. Buttons height 40, radius 12, 14/600.
  - Active: white bg, `#0c8a83` text, shadow sm.
  - Inactive: transparent, muted.
  - Options "English" / "Ελληνικά".
- **Export map as image** card (white, border, radius 18, padding 14, gap 14):
  - "Aspect ratio" 13/600 muted, then 4 option buttons (Free · 16:9 · 4:3 · 1:1).
  - "Image quality", then 3 option buttons (2x · 3x · 4x).
  - Option buttons: height 36, radius 12, 13/600. Active: bg `#e3f1ef`, border `rgba(15,169,160,.5)`, text teal. Inactive: white, `#efe4d2` border.
  - **Export map** button: height 46, radius 23, coral, `download` icon. Wire it to the existing `ExportMapImageButton` logic, rendering the map at the chosen ratio and scale. On mobile, save or share the PNG and then show the toast "Map image saved to Photos (16:9, 2x)".
  - Hint below: "Edits save to this device only." 12 muted.

### 5. Destination detail (full screen, single scroll)
Background `#fbf8f3`, z 20, scrolls as one column.
- **Hero** 260px tall: first photo cover (placeholder in mock), with three overlays:
  - Back button top-left 12: 42 round white, shadow, `arrow_back`.
  - Favorite toggle top-right 12: same style, heart red/filled when favorite.
  - Photo count pill bottom-right 12: 11/600, bg `rgba(43,33,24,.7)`, white, radius 10, "3 photos" / "1 photo".
- **Content**: padding `20px 20px 32px`, column gap 22.
  1. Name 26/800. Below it "from Home Base — Igoumenitsa" 13 muted.
  2. **Stat tiles**: 2-column grid, gap 10. Tile bg `#f6efe4`, radius 16, padding 12×14. Label 12 muted ("Distance" / "Drive"), value 18/700 ("24.9 km" / "30 min", "1 h 47 min"). Straight-line estimates append " (est.)". Keep the existing loading state "Calculating route…" in the value slot while OSRM is fetching.
  3. **Status segmented control** (same style as Language): `event` Planned / `check_circle` Visited, 18px icons. Active text color: coral for Planned, teal for Visited. Selecting Visited sets `visitedOn` to today if it's empty.
  4. **Visit log card** (only when visited): white, border, radius 18, padding 16, gap 12.
     - Header row: "VISIT LOG" label left, date 13 muted right.
     - 5 stars, 28px, tap to set the rating: filled `#ff9f52` FILL 1, empty `#d9cbb5`.
     - Note 14/1.5.
     - "My photos": 4-column grid gap 6, square radius 10. The last tile is a dashed (1.5px `#c9b89e`) `add_a_photo` tile that opens the camera/file picker.
  5. **Main attractions**: label, then rows with a coral `location_on` 18px + text. Empty state: "No attractions yet." 13 muted.
  6. **Photos** (if any): label, then a 3-column grid gap 8, square, radius 10, border. Caption mono 8px bottom-left. Tapping opens the existing lightbox (`PhotoGallery`).
  7. **Links** (if any): rows with an `open_in_new` 18px, teal 600 text (existing `LinksList`).
  8. **Actions** row, gap 8, each button flex 1, height 44, radius 22, 600:
     - Edit: bg `#fde9e0`, text coral.
     - Delete: bg `#fbe3e5`, text `#e63946`. Opens the confirm dialog.

### 6. Add / Edit destination, Edit main location (full-screen form)
- **Top bar** 64px with a bottom border:
  - `close` 48px icon button.
  - Title 18/700 ("Add destination" / "Edit destination" / "Edit main location").
  - **Save** pill: 40h, padding 0 20, teal bg, white 600.
- **Body**: padding 20×16, gap 16.
  - **Name**: label 13/600 muted, then an input 50h, radius 14, white, border, 15px. Placeholder "Untitled destination".
  - **Location card**: white, radius 18, padding 14, gap 12, border `#efe4d2` (turns `#e63946` on error).
    - "LOCATION" section label.
    - Lat / Lng inputs side by side: 44h, radius 12, bg `#fbf8f3`, mono 14, `inputMode=decimal`.
    - **Pick on map** button: 44h, radius 22, bg `#e3f1ef`, border `rgba(15,169,160,.35)`, teal 600, `pin_drop` icon.
    - Error text 12/600 `#e63946`: "Set a location first — type coordinates or pick on map."
  - Destination only:
    - **Main attractions** textarea, one per line, 3 rows, radius 14.
    - **Notes** textarea.
  - Photos and links editing can reuse the existing `DestinationEditForm` field-row pattern below these fields. The mock omits them for brevity.
- **Validation**: lat and lng must parse as finite numbers, or Save shows the error state. Name falls back to "Untitled destination" (home falls back to "Home base").
- **Save**:
  - New destination: added with status `planned` → opens its Detail → toast "Saved".
  - Edit: returns to Detail.
  - Home: returns to Settings.
  - Changed coordinates invalidate `routeInfo`, so it is refetched via OSRM. The mock shows a straight-line estimate meanwhile.
- **Close**: from an edit form, returns to Detail. Otherwise returns to the tab.

### 7. Pick on map (full screen, z 40)
- Full-screen map with all pins and labels.
- **Banner**: absolute top/left/right 12, bg `#0c8a83`, white, radius 18, padding `12px 8px 12px 16px`, shadow `0 6px 20px rgba(12,138,131,.3)`.
  - `touch_app` icon.
  - Text 13/600 "Tap anywhere on the map to set the location…" (existing `shell.pickingBanner`, reworded tap/click).
  - `close` button cancels.
- One tap on the map writes lat/lng (5 decimals) into the form and returns to the form. Cursor: crosshair.

### 8. Delete confirm dialog (z 50)
- Backdrop `rgba(15,12,8,.5)`, padding 32. Tapping the backdrop cancels.
- Card: bg `#fbf8f3`, radius 28, padding 24, gap 16.
  - Title 20/700 `Delete "{name}"?`.
  - Right-aligned buttons:
    - Cancel: text button, teal 600.
    - Delete: 40h pill, `#e63946`, white.
- Confirming removes the destination, closes Detail, and shows the toast "Deleted".

### Toast (z 60)
- Absolute left/right 16, bottom 16 (24 on full-screen screens).
- bg `#2b2118`, white 13, radius 14, padding 12×16, shadow. Teal `check_circle` icon `#2dd4c4`.
- Auto-hides after 2.6s.

---

## Interactions & Behavior
- **Selection** (`selectedDestinationId` already in store): set by tapping a pin or a carousel card, cleared by the card's ✕ or a tap on empty map. It is shared with the highlighted arrow and pin.
- **Navigation**: tab state plus a screen stack: `null | {detail,id} | {form, kind:'dest'|'home', id?}` and a `picking` flag. Android back should pop in this order: picker → form → detail → tab. Map back-gesture handling to the same `back()`.
- **Transitions** (suggested, the mock is instant):
  - Detail/form slide up or in, 250ms `cubic-bezier(.2,0,0,1)` (M3 emphasized).
  - Nav indicator background 200ms.
  - Pins animate size and color in 200ms (`transition: all .2s`).
- **Language** switch is global and instant via the existing `I18nProvider`. User content (names, notes) is not auto-translated. The existing "Translate" action (Gemini) can be added to the detail Actions row.
- **Favorites** toggle anywhere (list heart, detail heart) and update every view at once.
- **Status → Visited** reveals the Visit log. **Visited → Planned** hides the log but keeps its data.

## State Management
Extend `src/types/models.ts`:
```ts
type TripStatus = 'planned' | 'visited';
interface VisitLog { visitedOn?: string /* ISO date */; rating?: 1|2|3|4|5; note?: string; photos: Photo[] /* user photos */ }
interface Destination { /* existing fields */ status: TripStatus; favorite: boolean; visit?: VisitLog; }
interface ItineraryDay { id: string; date: string /* ISO */; stopIds: string[]; note?: string }
interface VacationMapData { version: 2; tripName?: string; mainLocation; destinations; itinerary: ItineraryDay[] }
```
- Bump `version` to 2 and migrate v1 data: `status:'planned'`, `favorite:false`, `itinerary:[]`. Import/export/localStorage persistence keep working unchanged.
- Store actions to add: `setStatus(id, s)`, `toggleFavorite(id)`, `setRating(id, n)`, `addVisitPhoto(id, photo)`, `updateVisit(id, patch)`, `setItinerary(days)`.
- UI-only state (component/local): `tab`, `screen`, `picking`, places `filter`, export `aspect` and `quality`, `toast`, `confirmDelete`, `displayMode`.
- Data fetching is unchanged: OSRM route on first detail open, cached in `routeInfo`.

## i18n
New keys (EN / EL). Reuse existing keys where they exist (`detail.*`, `form.*`, `shell.pickingBanner`, `export.*`):
`tabs.map` Map/Χάρτης · `tabs.places` Places/Μέρη · `tabs.trip` Trip/Ταξίδι · `tabs.settings` Settings/Ρυθμίσεις · `status.planned` Planned/Σχεδιασμένο · `status.visited` Visited/Επισκέφθηκα · `filter.all` All/Όλα · `filter.favorites` Favorites/Αγαπημένα · `detail.details` Details/Λεπτομέρειες · `detail.distance` Distance/Απόσταση · `detail.drive` Drive/Οδήγηση · `detail.fromHome` from {home}/από {home} · `visit.log` Visit log/Ημερολόγιο επίσκεψης · `visit.addPhoto` Add photo/Προσθήκη · `trip.day` Day/Ημέρα · `trip.freeDay` Free day/Ελεύθερη ημέρα · `trip.arrive` Arrive at home base/Άφιξη στη βάση · `trip.visitedOf` {v} of {n} visited/{v} από {n} επισκέψεις · `trip.unscheduled` Not scheduled/Χωρίς ημέρα · `places.empty` Nothing here yet./Τίποτα εδώ ακόμη. · `form.location` Location/Τοποθεσία · `form.needLocation` Set a location first — type coordinates or pick on map./Ορίστε πρώτα τοποθεσία. · `form.onePerLine` One per line/Ένα ανά γραμμή · `settings.language` Language/Γλώσσα · `export.aspect` Aspect ratio/Αναλογία διαστάσεων · `export.qualityLabel` Image quality/Ποιότητα εικόνας · `export.saved` Map image saved to Photos ({a}, {q})/Η εικόνα αποθηκεύτηκε ({a}, {q}) · `toast.saved` Saved/Αποθηκεύτηκε · `toast.deleted` Deleted/Διαγράφηκε · `photos.one` 1 photo/1 φωτογραφία · `photos.many` {n} photos/{n} φωτογραφίες · `routes.estimatedShort` est./εκτ.

The full EN/EL string table used by the mock is the `STR` object at the top of the logic script in `design/VacationApp.dc.html`.

## Assets
- **Icons:** Material Symbols Rounded (Google Fonts). Names used: `map, pin_drop, luggage, settings, conversion_path, scatter_plot, add, close, arrow_back, favorite, event, check_circle, check, star, location_on, open_in_new, add_a_photo, edit, download, touch_app, chevron_right`. Replacing the existing emoji/✕ glyphs with this set on mobile is intended.
- **Photos:** existing `Photo.url` data (Wikimedia / user URLs). The mock uses striped placeholders.
- **Map:** OSM tiles via the existing Leaflet setup (keep `crossOrigin="anonymous"` for export).
- No new image assets.

## Screenshots
Mobile screenshots are captured at 2x (824×1784) from the prototype in `screenshots/`. Use them for visual comparison; the README values are authoritative.
- `01-map.png` — Map tab, nothing selected (carousel)
- `02-map-selected.png` — Map tab, Syvota selected (bottom card, teal arrow/pin)
- `03-places.png` — Places tab with filters + FAB
- `04-trip.png` — Trip tab: dates, auto-plan, days, not-scheduled tray
- `05-settings.png` — Settings (home base, language, export)
- `06-detail.png` — Destination detail, visited, top of the scroll
- `07-form.png` — Add destination form
- `08-pick-on-map.png` — Pick-on-map mode
- `09-trip-dates.png` — Trip date picker sheet
- `10-add-to-day.png` — Add-to-day sheet

---

# Desktop (2a), the matching desktop layout
Same tokens, type, icons, data model, i18n and behaviors as mobile 1a. Only the shell and the placement of screens change. Source: `design/VacationDesktop.dc.html` (fixed 1440×900 artboard; production should be fluid, see Responsive).

## Breakpoints
- **< 600px:** mobile shell (1a, bottom nav).
- **600–1023px:** use the mobile shell, or the desktop shell with the panel as an overlay drawer. Your choice; it was not designed.
- **≥ 1024px:** desktop shell below.

## Shell
Row flex, full viewport height:
1. **Navigation rail**: 88px, bg `#f4eee5`, column, centered, padding `20px 0 16px`, gap 8.
   - Top: app mark, 44px circle, gradient `#ffd166→#ff7a4d`, ★ white 18px, margin-bottom 18.
   - Items (80 wide): indicator **56×32** radius 16, otherwise identical to the mobile nav item (active `#cfe9e6` / icon `#075e59` FILL 1; inactive `#5c4f40`; label 12px 700/500). Hover on the indicator: bg `#e9e0d2`.
   - Items: Map · Places · Trip · Settings.
   - Bottom (after a flex spacer): **language button** 48×40, radius 20, bg `#fbf8f3`, border `#e3d6c2`, 12/700, "EN" / "ΕΛ". Hover: white.
2. **Side panel**: 400px, bg `#fbf8f3`, right border `#efe4d2`, own vertical scroll (thin scrollbar). Shows the Places / Trip / Settings content. **Hidden when the Map item is active**, and the map then takes the full width.
3. **Map stage**: `flex:1`, `position:relative`, holds the map plus all floating UI.

## Side panel contents
Same components as mobile with desktop spacing (outer padding 24, titles 26/700 line-height 1.2, padding-top 28):
- **Places**:
  - Header: eyebrow "Epirus summer · 13–18 Jul 2026" 12/600 muted above the "Places" title.
  - Right of the header: **Add** pill, 40h, coral, `add` icon + "Add", shadow `0 4px 14px rgba(239,90,42,.28)`, hover `#e14d1e`.
  - Filter chips: single row, 32h, padding 0 11, gap 6, no wrapping (scrolls horizontally if needed).
  - List cards (padding 0 16, gap 8): as on mobile with a 64px thumb. **Selected** card: bg `#e3f1ef`, border `rgba(15,169,160,.45)`. Hover: border `#d9c7aa`. The heart has a round hover bg `#f4eee5`.
  - Clicking a card selects the destination and opens the floating Detail.
- **Trip**: same timeline as mobile. Day circles get a 1px `#efe4d2` border when they have open stops. Stop cards show the selected state like list cards. Clicking a stop opens Detail.
- **Settings**: same sections as mobile (padding 0 20, gap 24) plus a new **Orientation** row (Landscape / Portrait) under Aspect ratio. It reuses the existing `MapAspectControls` orientation and is dimmed to opacity .45 when the aspect is Free or 1:1.
  - Hint text: "Edits save to this browser only — export data to back up." If you keep the existing JSON Import / Export / Reset controls (`DataImportExportControls`), put them here as a "Data" section using the same option-button style.

## Map stage
- **Map**: fills the stage (Leaflet). Fit bounds with padding top 120, bottom 70, left 90, and right **470 while a floating panel is open** (otherwise 140), so pins never sit under the panel.
  - Desktop pins: 26px destination, 32px selected, 38px home. Labels 12/600, padding 3×9, radius 11, flipped to the left of the pin in the right 40% of the map.
  - Arrows: stroke 3 / 4 (selected), plus the 1px casing as in the codebase.
  - Attribution bottom-left 10px mono.
- **Map controls** (top 16, left 16, gap 8):
  - **Display mode** segmented: white pill, radius 22, padding 4, shadow md. Buttons 36h radius 18, 13/600, icons `conversion_path` "Arrows" / `scatter_plot` "Points". Active: bg `#e3f1ef`, teal. Inactive: `#5c4f40`. (Add "Routes" as a third button if you keep the OSRM-geometry mode.)
  - **Export image** button: 44h, white, radius 22, shadow md, coral `download` icon + "Export image", hover `#fbf8f3`. It exports with the current Settings aspect, orientation and quality, and then shows the toast "Map image downloaded (16:9, 2x)".
- **Export frame overlay**: when aspect ≠ Free, draw a centered rectangle of that ratio and orientation inside the stage (max size: stage minus 48 horizontal and 120 vertical, top offset 76).
  - Style: 2px dashed `rgba(255,255,255,.9)` border, radius 6. Everything outside is dimmed by `box-shadow: 0 0 0 2000px rgba(43,33,24,.28)`.
  - Label chip top-left: "16:9 · Landscape export frame", `#2b2118` bg, white 11/600.
  - `pointer-events:none`. This replaces the current letterbox-resize of the map; the export crops to the frame.
- **Floating Detail**: absolute top/right/bottom 16, **width 400**, bg `#fbf8f3`, radius 22, shadow `0 12px 40px rgba(120,72,20,.22)`, overflow hidden, column.
  - Scroll body: the mobile 1a detail with these changes:
    - Hero 210px, with **close** (38px white round, `close`) at top-right 12 and **favorite** to its left (right 58).
    - Title 24/800.
    - Content padding `20px 22px 28px`, gap 20.
    - Stars 26px.
  - **Sticky footer** (not in the scroll): padding 12×16, top border, Edit (`edit` icon) and Delete (`delete` icon) buttons 42h. Hover: Edit `#fbdccf`, Delete `#f8d4d7`.
  - Photos use `cursor: zoom-in` and open the existing lightbox.
- **Floating Form**: same container as Detail.
  - Header 64px: title 18/700 left, `close` 40px icon button right.
  - Body padding 20, the fields from mobile (inputs 46 / 42h).
  - Footer, right-aligned: **Cancel** text button (teal, hover bg `#e3f1ef`) and **Save** pill (teal, hover `#0a7973`).
  - "Pick on map" toggles picking; while active the button turns solid teal with the label "Click the map…".
- **Picking banner**: top 76, left 16 (under the map controls, clear of the form).
  - Teal pill, radius 18, `ads_click` icon, text "Click anywhere on the map to set the location" 13/600.
  - Outlined **Cancel** chip (32h, 1px `rgba(255,255,255,.4)`).
  - The map cursor becomes a crosshair. One click fills lat/lng and ends picking, and the form stays open.
- **Toast**: bottom-left of the stage (left 24, bottom 24), same style as mobile.
- **Delete dialog**: centered over the whole window, width 400, backdrop `rgba(15,12,8,.45)`. Clicking inside the card doesn't close it.

## Desktop interactions
- Clicking a pin, list card or Trip stop selects the destination and opens Detail. The selected pin and arrow turn teal, and the list row highlights.
- Clicking empty map closes Detail (not the Form). **Esc** closes in this order: dialog → picking → floating panel.
- Switching rail tabs keeps the floating Detail open.
- Map ↔ Places toggling resizes the map. Call Leaflet `invalidateSize()` and re-fit after the panel animates (suggested 200ms width transition).
- Hover states as listed above. All interactive elements show `cursor:pointer`.

## Desktop screenshots (1440×900, 1x)
- `screenshots/desktop-01-places.png` — Places panel, nothing selected
- `screenshots/desktop-02-detail.png` — Syvota selected, floating Detail
- `screenshots/desktop-03-trip.png` — Trip panel with Nikopolis open
- `screenshots/desktop-04-settings-export-frame.png` — Settings with the 16:9 export frame on the map
- `screenshots/desktop-05-map-full.png` — Map tab (panel hidden)
- `screenshots/desktop-06-form.png` — Add destination floating form
- `screenshots/desktop-07-pick-on-map.png` — Picking with the form open
- `screenshots/desktop-08-trip-dates.png` — Date picker popover
- `screenshots/desktop-09-add-to-day.png` — Add-to-day dialog

## Desktop implementation notes
- `AppShell.tsx` becomes `Rail + (Panel?) + MapStage`. `DestinationListSidebar` → Places panel, `DestinationDetailPanel` → floating Detail, `DestinationEditForm` / `MainLocationEditForm` → floating Form.
- The gradient header toolbar is removed. Its controls are redistributed:
  - Language → rail.
  - Display mode + export → map controls.
  - Aspect / orientation / quality → Settings.
  - Data import/export → Settings.
  - Fullscreen → the Map rail item (panel hidden). The browser fullscreen API is optional.
  - AI search → not designed; suggest adding it as a button in the Places header.

---

# Trip planning (mobile 1a + desktop 2a)
Replaces the static itinerary from the earlier sections. Same tokens and components. Screenshots: `04-trip`, `09-trip-dates`, `10-add-to-day`, `06-detail` (day chips), `desktop-03-trip`, `desktop-08-trip-dates`, `desktop-09-add-to-day`.

## Data model (supersedes `ItineraryDay`)
```ts
interface Trip { name: string; startDate: string /* ISO */; endDate: string; plan: string[][] /* plan[dayIndex] = ordered destination ids */ }
// VacationMapData.trip: Trip
```
- Days are **derived** from the date range (`N = end − start + 1`, max 30). Plan is stored by day index, so moving the trip keeps the plan.
- If a range change leaves `plan` longer than N, the extra days' stops count as unscheduled. Keep the array so extending again restores them.
- A destination is on at most one day. "Unscheduled" = destinations in no `plan[0..N-1]`.
- Deleting a destination removes it from `plan`.
- Store actions: `setTripDates(start, end)`, `moveStop(id, dayIndex | null, beforeIndex?)`, `autoPlan()`, `setPlan(plan)` (for undo).
- Dates are formatted with `Intl` (`en-GB` / `el-GR`, `timeZone:'UTC'`): day rows `{weekday:'short', day, month:'short'}` → "Mon 13 Jul". Range "13–18 Jul 2026" (cross-month "30 Jun–4 Jul 2026").

## Trip tab (mobile, panel on desktop)
- **Header**: trip name. Below it a row (gap 8, padding 0 16 / 0 20 desktop) with:
  - **Date button** (flex 1): 48h (44 desktop), white, border, pill. `edit_calendar` teal + range 14/600 + `expand_more`. Opens the date picker.
  - **Auto-plan** button: pill, bg `#fde9e0`, coral, `auto_awesome` + "Auto-plan". When nothing is unscheduled it's disabled: bg `#f4eee5`, text `#c9b89e`.
- Subline "6 days · 2 of 5 visited" 13 muted, then the progress bar.
- **Day row**: 44px day circle + connector (as before). Header row 44h:
  - Date 14/600.
  - Count "1 stop" / "2 stops" 12 muted.
  - **+** button 36px round, white, border, teal `add`. Opens the add-to-day sheet.
- **Stop card**: white, border, radius 14, padding 8/6 (desktop 6), gap 8, `draggable`. Contents in order:
  - `drag_indicator` `#c9b89e` (cursor grab).
  - Filled status icon.
  - Name 14/600 (ellipsis) and route 12 muted.
  - **×** remove-from-day button 36px, `#c9b89e`, hover bg `#f4eee5`.
  - Clicking the card opens Detail.
- **Empty day**: dashed 1.5px `#d9cbb5` box, min-height 52, radius 14, "Free day — tap + or drop a place here" 13 muted. Clicking it opens the sheet.
- **Not-scheduled tray**:
  - Placement: mobile = pinned to the bottom of the Trip screen above the nav (absolute, bg `#fbf8f3`, top border, shadow `0 -6px 20px rgba(120,72,20,.08)`). Desktop = `position: sticky; bottom:0` inside the panel.
  - Label: `inventory_2` + "Not scheduled (3) · drag onto a day" 12/700 muted.
  - Chips: horizontal scroll. 44h (36 desktop), white, border, pill. `drag_indicator` + status dot + name 13/600. Draggable; clicking a chip opens Detail.
  - The tray is shown when there are unscheduled places **or while dragging**, when it becomes a drop target that unschedules ("Drop here to unschedule").
- **Scroll padding**: on mobile the scroll area adds 116px bottom padding while the tray is visible.

## Drag & drop
- Sources: stop cards and tray chips. Targets: each day row (append), each stop card (insert before/after by pointer Y vs card midpoint), and the tray (unschedule).
- **Feedback**:
  - Dragged item opacity .4.
  - Hovered day's stop zone bg `#e3f1ef` and its empty-day dashed border turns teal.
  - Insertion line: 3px teal (`box-shadow: 0 -3px 0 0 #0c8a83` on the card after the gap, or `0 3px` under the last card).
  - Hovered tray bg `#e3f1ef`.
- Moving within the same day adjusts the index for the removed item.
- **Implementation**: the prototype uses HTML5 DnD (mouse). In production use **dnd-kit** (or similar) with a pointer + touch sensor: **long-press 250ms to start on touch**, keyboard sensor for a11y, auto-scroll near the edges, and a drag overlay that follows the finger.

## Add-to-day sheet
- **Mobile**: bottom sheet, z above nav. Scrim `rgba(15,12,8,.45)`, radius 28 top, 36×4 handle `#d9cbb5`, max-height 80%.
- **Desktop**: centered dialog 420w, radius 24, scrim .35.
- Title "Add to day 3" 20/700, date subline.
- Rows list **all** destinations in list order, min-height 56, radius 14, hover `#f4eee5`:
  - `check_box` (teal, FILL 1) / `check_box_outline_blank` (`#c9b89e`).
  - Name 15/600 and route.
  - Tag "Day 5" (`#f4eee5` pill, 11/600) when the place is on another day.
- Ticking toggles immediately. A place already on another day is **moved** here, and unticking unschedules it.
- **Done**: full-width 48h teal pill on mobile, right-aligned 42h pill on desktop. Closes the sheet. Scrim click and Esc also close it.

## Date picker
- **Mobile**: bottom sheet (same chrome). **Desktop**: popover 360w anchored under the date button (left 108, top 136 in the 1440 layout), radius 22, border, shadow `0 12px 40px rgba(120,72,20,.25)`. A transparent click-catcher closes it, and so does Esc.
- Title "Trip dates", hint "Tap the first and last day of your trip".
- **Start / End fields**: 2-column grid, white, radius 14, 1.5px border.
  - The field the next tap will set is teal `#0c8a83`, the other `#efe4d2`.
  - Label 11/600 uppercase muted, value 15/700 "Mon 13 Jul" or "—".
- **Month header**: "July 2026" 15/700 with `chevron_left` / `chevron_right` 40px buttons.
- **Grid**: weeks start **Monday**, weekday letters 12/600 muted, cells 44h (42 desktop).
  - Day number in a 40px circle (38 desktop), 14/500.
  - Endpoints: teal circle, white 700.
  - In-range band `#e3f1ef` across the cells, rounded 22px at the start and end.
  - Today: 1px `#c9b89e` ring.
- **Selection logic**:
  - First tap sets the start and clears the end.
  - Second tap sets the end. If it is before the start, it replaces the start instead.
  - A tap after both are set starts over.
  - Saving with only a start creates a 1-day trip.
- **Footer**: "6 days" 14/600, then Cancel (teal text) and **Save** (teal pill; `#c9b89e` until a start is picked). Save shows the toast "Trip dates saved".

## Destination detail: "Trip day" chips
- New section between the status control and the visit log, labeled "TRIP DAY".
- Horizontally scrolling chips that bleed to the screen edges: `margin: 0 -20px; padding: 0 20px` (22 on desktop). One chip per trip day: "**Day 2** 14 Jul" (700 + 500 at .8 opacity).
- Chip sizes: 44h mobile, 36h desktop. Pill, 13px. Active: teal bg/border, white text. Inactive: white, border `#efe4d2`.
- Tapping a chip moves the place to that day. Tapping the active chip unschedules it.

## Auto-plan
1. Take the unscheduled destinations and sort them by compass bearing from the home base.
2. Candidate days are all days except day 1 (arrival) when the trip has more than 2 days.
3. For each place in order:
   - If it is within 25° bearing and 40 km of the previous place, and that day has fewer than 2 stops, put it on the same day.
   - Otherwise put it on the candidate day with the fewest stops (earliest on ties).
4. Apply at once, then show the toast "Spread 3 places over your days" with an **Undo** action (5s, teal `#2dd4c4` text button) that restores the previous plan.

Seed result: Nikopolis + Preveza → Wed 15 Jul, Lefkada → Fri 17 Jul. Optional improvement: weigh by OSRM drive time and cap the day total.

## Toast with action
Same toast as before, plus an optional trailing text button (13/700, `#2dd4c4`). On mobile the toast sits above the tray when the tray is visible.

## New i18n keys (EN / EL)
`trip.datesTitle` Trip dates / Ημερομηνίες ταξιδιού · `trip.tapFirstLast` Tap the first and last day of your trip / Πατήστε την πρώτη και την τελευταία ημέρα του ταξιδιού · `trip.start` Start / Έναρξη · `trip.end` End / Λήξη · `trip.nDays` {n} days / {n} ημέρες · `trip.oneDay` 1 day / 1 ημέρα · `trip.autoPlan` Auto-plan / Αυτόματο πλάνο · `trip.autoPlanned` Spread {n} places over your days / Μοιράστηκαν {n} μέρη στις ημέρες σας · `common.undo` Undo / Αναίρεση · `trip.addToDay` Add to day {n} / Προσθήκη στην ημέρα {n} · `common.done` Done / Τέλος · `trip.onDay` Day {n} / Ημέρα {n} · `trip.unschedTray` Not scheduled ({n}) · drag onto a day / Χωρίς ημέρα ({n}) · σύρετε σε μια ημέρα · `trip.dropToUnschedule` Drop here to unschedule / Αφήστε εδώ για αφαίρεση από ημέρα · `trip.dropHere` Free day — tap + or drop a place here / Ελεύθερη ημέρα — πατήστε + ή αφήστε ένα μέρος εδώ · `detail.tripDay` Trip day / Ημέρα ταξιδιού · `trip.nStops` {n} stops / {n} στάσεις · `trip.oneStop` 1 stop / 1 στάση · `trip.removeFromDay` Remove from day / Αφαίρεση από την ημέρα · `trip.datesSaved` Trip dates saved / Οι ημερομηνίες αποθηκεύτηκαν

## Prototype pointers
In both `design/VacationApp.dc.html` and `design/VacationDesktop.dc.html`:
- **Shared logic**: `TRIP0`, the date helpers and `tripVals()` sit above the class. `moveTo`, `dragStart` / `dragEnd`, `pickDate`, `saveCal` and `autoPlan` are on the class.
- **Markup**: blocks `TRIP TAB` + `TRIP SHEETS` (mobile), the `showTrip` panel block and the `showCal` / `showSheet` overlays (desktop).

## Files
- `design/Vacation Map Mobile.dc.html` — canvas with the mobile directions (turn 1; **1a** is the one to build) and the desktop layout (turn 2, **2a**).
- `design/VacationDesktop.dc.html` — desktop prototype (2a). Same structure: template, then a logic script with `STR`, data and `mapEl` (fit padding, label flipping, pick-to-coordinates).
- `design/VacationApp.dc.html` — the mobile prototype. Markup for 1a is in the blocks marked `MAP TAB` (`showMapA`), `PLACES TAB`, `TRIP TAB`, `SETTINGS TAB`, `DETAIL A`, `FORM`, `PICK ON MAP`, `DELETE CONFIRM` and `NAV A`. The logic script holds the state, strings and sample data.
- `design/android-frame.jsx`, `design/support.js` — needed only to open the prototype locally.
- Codebase files to touch: `src/components/layout/AppShell.tsx` (mobile shell), `src/components/map/*` (labels, visited check, fit padding), `src/components/panels/*` (detail/form restyle), `src/store/mapDataStore.ts` and `src/types/models.ts` (new fields and migration), `src/i18n/translations.ts`, `src/index.css` / `src/App.css` (tokens).
