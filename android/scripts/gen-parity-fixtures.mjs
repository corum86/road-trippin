#!/usr/bin/env node
// Runs the web app's pure logic (src/services) on a spread of inputs and
// records the results. The Android unit tests replay the same inputs through
// the Kotlin port and expect identical output (see ParityTest.kt).
//
//   node android/scripts/gen-parity-fixtures.mjs
//
// Needs Node 23.6+ (it imports the TypeScript sources directly).
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

// the sources import each other without file extensions, as bundlers allow
registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith('.') && context.parentURL?.startsWith('file:')) {
      const base = fileURLToPath(new URL(specifier, context.parentURL));
      if (!existsSync(base) && existsSync(`${base}.ts`)) return nextResolve(`${specifier}.ts`, context);
    }
    return nextResolve(specifier, context);
  },
});

const load = (path) => import(pathToFileURL(resolve(root, path)).href);
const dates = await load('src/services/dates.ts');
const geo = await load('src/services/geo.ts');
const tripPlan = await load('src/services/tripPlan.ts');
const suggestions = await load('src/services/tripSuggestions.ts');
const routeFormat = await load('src/services/routeFormat.ts');
const placeNames = await load('src/services/placeNames.ts');
const photon = await load('src/services/photonService.ts');
const range = await load('src/components/screens/rangeSelection.ts');
const store = await (async () => {
  // the store persists to localStorage on creation
  globalThis.localStorage = { getItem: () => null, setItem: () => {}, removeItem: () => {} };
  return load('src/store/mapDataStore.ts');
})();
const { translations } = await load('src/i18n/translations.ts');

// deterministic pseudo-random numbers, so the fixtures only change when the logic does
function mulberry32(seed) {
  let a = seed;
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rand = mulberry32(20261006);
const int = (min, max) => Math.floor(rand() * (max - min + 1)) + min;
const pick = (items) => items[int(0, items.length - 1)];
const sample = (items, p = 0.5) => items.filter(() => rand() < p);

const cases = [];
const record = (fn, args, out) => cases.push({ fn, args, out: out === undefined ? null : out });

// --- dates -----------------------------------------------------------------
const DATES = ['2026-07-13', '2026-12-31', '2024-02-28', '2024-02-29', '2025-03-01', '2026-01-01', '2026-09-05', '2026-10-25'];
for (const d of DATES) {
  for (const n of [-400, -31, -1, 0, 1, 6, 29, 30, 365]) record('addDays', [d, n], dates.addDays(d, n));
  for (const e of DATES) {
    record('diffDays', [d, e], dates.diffDays(d, e));
    record('clampTripEnd', [d, e], tripPlan.clampTripEnd(d, e));
    record('tripDayCount', [d, e], tripPlan.tripDayCount({ startDate: d, endDate: e }));
  }
  record('weekdayIndex', [d], dates.weekdayIndex(d));
  for (const lang of ['en', 'el']) {
    record('formatDayLabel', [d, lang], dates.formatDayLabel(d, lang));
    record('formatShortDate', [d, lang], dates.formatShortDate(d, lang));
    record('formatMonthTitle', [d.slice(0, 7), lang], dates.formatDate(`${d.slice(0, 7)}-01`, lang, { month: 'long', year: 'numeric' }));
    record('formatMonthYear', [d, lang], dates.formatDate(d, lang, { month: 'short', year: 'numeric' }));
    for (const e of ['2026-07-13', '2026-07-18', '2026-08-02', '2027-01-03']) {
      record('formatDateRange', [d, e, lang], dates.formatDateRange(d, e, lang));
    }
  }
}
for (const m of ['2026-01', '2026-07', '2024-02', '2025-02', '2026-12']) {
  for (const n of [-13, -1, 0, 1, 12, 25]) record('addMonths', [m, n], dates.addMonths(m, n));
  record('daysInMonth', [m], dates.daysInMonth(m));
}
record('tripDayCount', [null, null], tripPlan.tripDayCount({ startDate: null, endDate: null }));
record('tripDayCount', ['2026-07-13', null], tripPlan.tripDayCount({ startDate: '2026-07-13', endDate: null }));
for (const v of ['2026-07-13', '2026-7-13', '2026-13-45', '2026-02-31', '', 'abcd-ef-gh', '2026-07-13T10:00:00Z']) {
  record('isIsoDate', [v], dates.isIsoDate(v));
}
record('addDays', ['2026-02-31', 0], dates.addDays('2026-02-31', 0));
record('addDays', ['2026-13-45', 1], dates.addDays('2026-13-45', 1));

// --- geo ---------------------------------------------------------------------
const HOME = { lat: 39.507780595135074, lng: 20.26306024441927 };
const place = () => ({ lat: HOME.lat + (rand() - 0.5) * 3, lng: HOME.lng + (rand() - 0.5) * 3 });
for (let i = 0; i < 20; i++) {
  const a = place();
  const b = place();
  record('haversine', [a, b], geo.haversineDistanceMeters(a, b));
  const from = { x: rand() * 800, y: rand() * 800 };
  const to = { x: rand() * 800, y: rand() * 800 };
  const bow = (rand() - 0.5) * 0.6;
  const control = geo.bezierControlPoint(from, to, bow);
  record('bezierControlPoint', [from, to, bow], control);
  const gap = rand() * 40;
  record('trimQuadraticBezier', [from, control, to, gap], geo.trimQuadraticBezier(from, control, to, gap));
}
for (const s of ['', 'a', 'Preveza', '3211655f-bc0c-4cf0-9ef0-1963e6b96908', 'Ηγουμενίτσα — βάση', '😀 emoji', 'x'.repeat(200)]) {
  record('hashString', [s], geo.hashString(s));
}
for (let i = 0; i < 40; i++) {
  const id = `${int(0, 1e9).toString(16)}-${int(0, 1e9).toString(16)}-4${int(0, 1e9).toString(16)}`;
  record('hashString', [id], geo.hashString(id));
}
for (const name of ['Home Base — Igoumenitsa', 'Igoumenitsa', 'A — B — C', '—', 'Trailing —', 'Dash - not em']) {
  record('shortPlaceName', [name], placeNames.shortPlaceName(name));
}

// --- route formatting ----------------------------------------------------------
for (const lang of ['en', 'el']) {
  const t = (key) => translations[lang][key];
  for (const m of [0, 49, 50, 949, 950, 24949, 24950, 86581.6, 123456.78, 999950]) {
    record('formatDistance', [m, lang], routeFormat.formatDistance(m, t));
  }
  for (const s of [0, 29, 30, 89, 90, 1800, 3570, 3599, 3600, 4546.7, 6431, 7200, 36000.5]) {
    record('formatDuration', [s, lang], routeFormat.formatDuration(s, t));
  }
}

// --- plan editing --------------------------------------------------------------
const IDS = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'];
function randomPlan(days) {
  return Array.from({ length: days }, () => {
    const ids = sample(IDS, 0.3);
    for (let i = ids.length - 1; i > 0; i--) {
      const j = int(0, i);
      [ids[i], ids[j]] = [ids[j], ids[i]];
    }
    return ids;
  });
}
for (let i = 0; i < 120; i++) {
  const plan = randomPlan(int(0, 6));
  const id = pick(IDS);
  const stops = plan.flatMap((ids, day) => ids.map((stopId, index) => ({ id: stopId, day, index })));
  const from = stops.length > 0 && rand() < 0.7 ? pick(stops) : { id, day: null };
  const toDay = rand() < 0.15 ? null : int(0, 7);
  const before = rand() < 0.5 ? undefined : int(0, 4);
  record('movePlanStop', [plan, from, toDay, before ?? null], tripPlan.movePlanStop(plan, from, toDay, before));
  const day = int(0, 7);
  record('togglePlanDay', [plan, id, day], tripPlan.togglePlanDay(plan, id, day));
  record('swapPlanStop', [plan, day, int(0, 3), id], tripPlan.swapPlanStop(plan, day, int(0, 3), id));
  record('removeFromPlan', [plan, id], tripPlan.removeFromPlan(plan, id));
}
// swapPlanStop draws its index twice above; record cases whose args are exactly what ran
cases.splice(0, cases.length, ...cases.filter((c) => c.fn !== 'swapPlanStop'));
for (let i = 0; i < 120; i++) {
  const plan = randomPlan(int(0, 6));
  const day = int(0, 7);
  const index = int(0, 3);
  const id = pick(IDS);
  record('swapPlanStop', [plan, day, index, id], tripPlan.swapPlanStop(plan, day, index, id));
}

// --- distance planner ------------------------------------------------------------
function randomDestinations(count) {
  return Array.from({ length: count }, (_, i) => {
    const location = place();
    const routed = rand() < 0.6;
    return {
      id: `d${i}`,
      name: `Place ${i}`,
      location,
      attractions: [],
      photos: [],
      links: [],
      ...(routed
        ? {
            routeInfo: {
              distanceMeters: int(2000, 250000),
              durationSeconds: int(300, 12000),
              source: 'osrm',
              fetchedAt: '2026-07-12T19:16:42.066Z',
            },
          }
        : {}),
      status: rand() < 0.3 ? 'visited' : 'planned',
      favorite: false,
    };
  });
}
const home = { name: 'Home Base — Igoumenitsa', location: HOME };
for (let i = 0; i < 60; i++) {
  const destinations = randomDestinations(int(0, 12));
  const dayCount = int(1, 12);
  const startDate = pick(DATES);
  // sometimes longer than the date range: the hidden days must survive
  const planDaysStored = dayCount + (rand() < 0.3 ? int(1, 3) : 0);
  const ids = destinations.map((d) => d.id);
  const plan = Array.from({ length: rand() < 0.2 ? 0 : planDaysStored }, () => sample(ids, 0.18));
  const trip = { name: '', startDate, endDate: dates.addDays(startDate, dayCount - 1), plan };

  const items = sample(destinations, 0.7).map((d) => tripPlan.toPlanItem(d, home));
  const base = rand() < 0.3 ? null : tripPlan.visiblePlan(trip);
  const fromDay = rand() < 0.5 ? 0 : int(0, dayCount + 1);
  record('planDays', [items, dayCount, HOME, base, fromDay], tripPlan.planDays(items, dayCount, HOME, base, fromDay));
  record('scheduleUnscheduled', [trip, home, destinations], tripPlan.scheduleUnscheduled(trip, home, destinations));
  record('firstOpenDay', [trip, destinations], tripPlan.firstOpenDay(trip, destinations));
  const replanFrom = int(0, dayCount);
  const include = rand() < 0.5;
  record(
    'replanTrip',
    [trip, home, destinations, replanFrom, include],
    tripPlan.replanTrip(trip, home, destinations, replanFrom, include),
  );
  record(
    'unscheduled',
    [trip, destinations],
    tripPlan.unscheduledDestinations(trip, destinations).map((d) => d.id),
  );
  record(
    'daysByDestination',
    [trip],
    Object.fromEntries(tripPlan.daysByDestination(trip)),
  );
}
for (let i = 0; i < 30; i++) {
  const drives = Array.from({ length: int(0, 4) }, () => rand() * 180);
  record('dayDriveMinutes', [drives], tripPlan.dayDriveMinutes(drives));
}

// --- suggestions -------------------------------------------------------------------
const STYLES = ['relaxed', 'active', 'sightseeing', 'culture', 'food', 'nature'];
const GROUPS = ['couple', 'family', 'friends'];
const MUSTS = ['beach', 'food', 'kids', 'nightlife'];
function randomSuggestions(count) {
  return Array.from({ length: count }, (_, i) => ({
    id: `s${i}`,
    name: `Suggestion ${i}`,
    location: place(),
    blurb: 'A place',
    styles: sample(STYLES, 0.4),
    groups: sample(GROUPS, 0.5),
    budget: int(1, 3),
    mustHaves: sample(MUSTS, 0.4),
    ferry: rand() < 0.2,
    // a few exact ties, to pin down the ordering of equals
    driveMinutes: rand() < 0.3 ? 60 : rand() * 180,
    distanceKm: rand() * 200,
    estimated: rand() < 0.2,
  }));
}
for (let i = 0; i < 60; i++) {
  const list = randomSuggestions(int(0, 14));
  const prefs = {
    styles: sample(STYLES, 0.4),
    group: rand() < 0.2 ? null : pick(GROUPS),
    mustHaves: sample(MUSTS, 0.4),
    budget: rand() < 0.3 ? null : int(1, 3),
  };
  record(
    'rankSuggestions',
    [list, prefs],
    suggestions.rankSuggestions(list, prefs).map((r) => ({
      id: r.suggestion.id,
      score: r.score,
      reasons: r.reasons.map((reason) => `${reason.kind}:${reason.value}`),
    })),
  );
  const limit = rand() < 0.2 ? null : pick([30, 60, 90, 120]);
  const ferry = rand() < 0.5;
  record('reachable', [list, limit, ferry], list.filter((s) => suggestions.isReachable(s, limit, ferry)).map((s) => s.id));
}
for (let d = -1; d <= 31; d++) record('recommendedPlaceCount', [d], suggestions.recommendedPlaceCount(d));

// --- calendar range ------------------------------------------------------------------
for (let i = 0; i < 60; i++) {
  const start = rand() < 0.3 ? null : pick(DATES);
  const end = start && rand() < 0.5 ? dates.addDays(start, int(0, 40)) : null;
  const date = pick(DATES);
  record('pickRangeDate', [{ start, end }, date], range.pickRangeDate({ start, end }, date));
  record('rangeEndOf', [{ start, end }], range.rangeEndOf({ start, end }));
}

// --- text the model garbled ---------------------------------------------------------------
const aiFindings = await load('src/services/aiFindings.ts');
const TEXTS = [
  'Το ιστορικό κάστρο της πόλης χρονολογείται από τη βυζαντινή εποχή.',
  'Λitapoitaúpi To Bapoufti afetepó tis mótis, êva fpocifiko paiko-avtoptiko pvnúmio tov 16ov divoa me Evtutfosiako fpoloyioko fipyo.',
  'Βρίσκεται στο πάρκο Λitharitsia και φιλοξενεί ευρήματα από όλη την Ήπειρο.',
  'Στο Πάρκο Λιμενάρχη Μουстаκη φιλοξενεί ευρήματα από όλη την Ήπειρο.',
  'Βρίσκεται στο χωριό Μουζα这一切ι και φιλοξενεί κέρινα ομοιώματα.',
  'Ιστορία του Αλή Π。 Δια',
  'τα παραδοσιακά ταverna με τοπικές γεύσεις και το Λitharitsia πάρκο',
  'Its Kale (Ιτς Καλέ): η εσωτερική ακρόπολη του κάστρου, μνημείο UNESCO.',
  'Its Kale',
  'A Venetian castle above a colourful harbour town.',
  'Parga — καλό',
  '',
];
for (const text of TEXTS) for (const lang of ['el', 'en']) record('looksGarbled', [text, lang], aiFindings.looksGarbled(text, lang));

// --- migration ------------------------------------------------------------------------
const seed = JSON.parse(readFileSync(resolve(root, 'public/data/vacation-data.json'), 'utf8'));
const explicitTrip = { name: 'Epirus summer', startDate: '2026-07-13', endDate: '2026-07-18' };
const seedIds = seed.destinations.map((d) => d.id);
const migrations = [
  // v1 seed, given a trip so the result doesn't depend on today's date
  { ...seed, trip: { ...explicitTrip, plan: [[], [seedIds[0], seedIds[0], 'gone'], [seedIds[1]]] } },
  // v2: dated itinerary days plus a separate trip name
  {
    version: 2,
    tripName: 'Old trip',
    mainLocation: seed.mainLocation,
    destinations: seed.destinations.map((d, i) => ({
      ...d,
      status: i % 2 ? 'visited' : 'planned',
      favorite: i === 0,
      visit: i % 2 ? { visitedOn: '2026-07-14', rating: 4, note: 'Nice', photos: [{ id: 'p', url: 'https://example.com/p.jpg' }] } : undefined,
    })),
    itinerary: [
      { id: 'x', date: '2026-07-15', stopIds: [seedIds[1]] },
      { id: 'y', date: '2026-07-13', stopIds: [seedIds[0], seedIds[2]] },
      { id: 'z', date: 'not a date', stopIds: [seedIds[0]] },
      { id: 'w', date: '2026-07-15', stopIds: [seedIds[0]] },
    ],
  },
  // v3 with planner answers, suggestions (some malformed) and an over-long range
  {
    version: 3,
    mainLocation: seed.mainLocation,
    destinations: seed.destinations,
    trip: {
      name: 'Family trip · Jul 2026',
      startDate: '2026-07-01',
      endDate: '2026-09-30',
      plan: [[seedIds[2]], [], [seedIds[2], seedIds[1]]],
      preferences: { maxDriveMinutes: 90, ferry: false, group: 'family', styles: ['relaxed', 'bogus', 'relaxed', 'food'], budget: 2, mustHaves: ['beach'] },
      suggestions: [
        { id: 's1', name: ' Parga ', location: { lat: 39.28, lng: 20.4 }, blurb: 'Colourful seaside town', styles: ['relaxed'], groups: ['family', 'couple'], budget: 2, mustHaves: ['beach', 'food'], ferry: false, driveMinutes: 55.5, distanceKm: 50.2, estimated: false },
        { id: 's2', name: 'No location' },
        { id: 's3', name: 'Paxos', location: { lat: 39.2, lng: 20.18 }, budget: 7, ferry: true, driveMinutes: -4, distanceKm: 'far' },
        'nonsense',
      ],
    },
  },
  // a cleared map: no home base, no trip dates
  { version: 4, mainLocation: null, destinations: [], trip: { name: '', startDate: null, endDate: null, plan: [] } },
  // places and a plan, but the dates were cleared
  {
    version: 4,
    mainLocation: null,
    destinations: seed.destinations,
    trip: { name: 'Someday', startDate: null, endDate: null, plan: [[seedIds[0]], [seedIds[1], 'gone']] },
  },
  // preferences without a valid group are dropped
  {
    version: 3,
    mainLocation: seed.mainLocation,
    destinations: [],
    trip: { ...explicitTrip, endDate: '2026-07-01', plan: 'oops', preferences: { group: 'solo', ferry: true } },
  },
];
for (const input of migrations) {
  record('migrate', [input], JSON.parse(JSON.stringify(store.migrateVacationMapData(structuredClone(input)))));
}

// --- place search ------------------------------------------------------------------
const feature = (properties, coordinates = [20.39982, 39.28526]) => ({
  type: 'Feature',
  properties,
  geometry: { type: 'Point', coordinates },
});
const epirus = { county: 'Preveza Regional Unit', state: 'Epirus and Western Macedonia', country: 'Greece' };
const parga = { osm_type: 'N', osm_id: 283363565, osm_key: 'place', osm_value: 'town', type: 'city', name: 'Parga', ...epirus };
const photonAnswers = [
  [],
  // a business and a street named after the town come back ahead of it; its boundary repeats it
  [
    feature({ osm_type: 'N', osm_id: 10014245517, type: 'house', name: 'Parga', street: 'Asagia', city: 'Parga', ...epirus }),
    feature({ osm_type: 'W', osm_id: 128765304, type: 'street', name: 'Pargas', city: 'Igoumenitsa', country: 'Greece' }, [20.27, 39.5]),
    feature(parga),
    feature({ ...parga, osm_type: 'R', osm_id: 2225507, osm_value: 'administrative' }, [20.41, 39.29]),
    feature({ osm_type: 'W', osm_id: 190570770, type: 'other', name: 'Valtos Beach', city: 'Parga', ...epirus }, [20.3869, 39.2853]),
    feature({ osm_type: 'R', osm_id: 13100842, name: 'Sivota Islands', city: 'Sivota', country: 'Greece' }, [20.22, 39.4]),
  ],
  // every layer Photon names
  ['house', 'street', 'locality', 'district', 'city', 'county', 'state', 'country', 'other'].map((type, i) =>
    feature({ osm_type: 'N', osm_id: i + 1, type, name: `A ${type}`, country: 'Greece' }, [20 + i / 10, 39 - i / 10]),
  ),
  // addresses go by street and number when they have no name
  [
    feature({ osm_type: 'N', osm_id: 1, type: 'house', housenumber: '10', street: 'Downing Street', city: 'London', state: 'England', country: 'United Kingdom' }, [-0.1276, 51.5034]),
    feature({ osm_type: 'N', osm_id: 2, type: 'house', name: '', street: 'Odos Pargas', city: 'Preveza' }),
    feature({ osm_type: 'N', osm_id: 3, type: 'house', housenumber: '7', city: 'Nowhere' }),
  ],
  // nothing to offer: no name, a blank one, no coordinates, coordinates as text, nothing at all
  [
    feature({ osm_type: 'N', osm_id: 1, type: 'city', country: 'Greece' }),
    feature({ osm_type: 'N', osm_id: 2, type: 'city', name: '   ', country: 'Greece' }),
    { type: 'Feature', properties: { osm_type: 'N', osm_id: 3, type: 'city', name: 'No geometry' } },
    { type: 'Feature', properties: { osm_type: 'N', osm_id: 4, type: 'city', name: 'Null geometry' }, geometry: null },
    feature({ osm_type: 'N', osm_id: 5, type: 'city', name: 'Half a point' }, [20.4]),
    feature({ osm_type: 'N', osm_id: 6, type: 'city', name: 'Text point' }, ['20.4', '39.28']),
    { type: 'Feature', geometry: { type: 'Point', coordinates: [20.4, 39.28] } },
    {},
  ],
  // the detail leaves out the name itself and anything said twice
  [
    feature({ osm_type: 'R', osm_id: 71525, type: 'city', name: 'Paris', state: 'Île-de-France', country: 'France' }, [2.32, 48.8589]),
    feature({ osm_type: 'R', osm_id: 7444, type: 'district', name: 'Paris', city: 'Paris', state: 'Île-de-France', country: 'France' }, [2.3484, 48.8535]),
    feature({ osm_type: 'R', osm_id: 2171347, type: 'country', name: 'Luxembourg', country: 'Luxembourg' }, [6.13, 49.81]),
    feature({ osm_type: 'N', osm_id: 52943358, type: 'city', name: ' Luxembourg ', county: 'Canton Luxembourg', state: 'Luxembourg', country: 'Luxembourg' }, [6.13, 49.61]),
    feature({ osm_type: 'N', osm_id: 9, type: 'city', name: 'Ηγουμενίτσα', county: 'Θεσπρωτία', state: 'Θεσπρωτία', country: 'Ελλάς' }, [20.2656, 39.5034]),
    feature({ osm_type: 'N', osm_id: 10, type: 'locality', name: 'Alone' }, [0, 0]),
  ],
];
for (const features of photonAnswers) {
  record('placeMatchesFromPhoton', [features], photon.placeMatchesFromPhoton(features));
}

const target = resolve(root, 'android/app/src/test/resources/parity/fixtures.json');
mkdirSync(dirname(target), { recursive: true });
writeFileSync(target, `${JSON.stringify(cases)}\n`);
const counts = {};
for (const c of cases) counts[c.fn] = (counts[c.fn] ?? 0) + 1;
console.log(`Wrote ${cases.length} cases to ${target}`);
console.log(counts);
