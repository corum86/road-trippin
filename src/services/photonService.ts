import type { LatLng } from '../types/models';
import type { Lang } from '../i18n/translations';
import { haversineDistanceMeters } from './geo';

const PHOTON_REVERSE_URL = 'https://photon.komoot.io/reverse';
const PHOTON_SEARCH_URL = 'https://photon.komoot.io/api';
// the public server never returns more than this, nearest first
const PHOTON_LIMIT = 50;
const SEARCH_LIMIT = 8;
/** Shorter text matches half the world; wait for this much before searching. */
export const SEARCH_MIN_LENGTH = 2;
// ask for more than the view so small pans stay inside what is loaded
const RADIUS_SLACK = 1.5;
// a cut-off answer holds the places nearest its centre; asking again from
// this close (as a share of the distance it reached) would return the same
const SAME_ANSWER_SHARE = 0.3;

export type PlaceKind = 'city' | 'town' | 'village';

/** A settlement that can be picked from the map. */
export interface MapPlace {
  id: string;
  name: string;
  kind: PlaceKind;
  location: LatLng;
}

interface PhotonResponse {
  features?: Array<{
    properties?: { osm_type?: string; osm_id?: number; osm_value?: string; name?: string };
    geometry?: { coordinates?: [number, number] };
  }>;
}

// Photon speaks en/de/fr; 'default' is the local name (Greek within Greece)
type PhotonLang = 'en' | 'default';

function photonLang(lang: Lang): PhotonLang {
  return lang === 'en' ? 'en' : 'default';
}

/** Which settlements to offer at a zoom level: roughly what the basemap labels there. */
export function placeKindsForZoom(zoom: number): PlaceKind[] {
  if (zoom < 8) return ['city'];
  if (zoom < 11) return ['city', 'town'];
  return ['city', 'town', 'village'];
}

// One request per group. The rarer kinds get their own so the nearest-50
// cut-off of a crowded group (villages) can't push them out.
function requestGroups(zoom: number): PlaceKind[][] {
  if (zoom < 8) return [['city']];
  if (zoom < 11) return [['city'], ['town']];
  return [['city', 'town'], ['village']];
}

interface Coverage {
  lang: PhotonLang;
  kinds: string;
  center: LatLng;
  /** every place of `kinds` within this distance of `center` is cached */
  radiusKm: number;
  truncated: boolean;
}

const cache = new Map<PhotonLang, Map<string, MapPlace>>();
const coverage: Coverage[] = [];

function distanceKm(a: LatLng, b: LatLng): number {
  return haversineDistanceMeters(a, b) / 1000;
}

function isCovered(lang: PhotonLang, kinds: string, center: LatLng, radiusKm: number): boolean {
  return coverage.some((c) => {
    if (c.lang !== lang || c.kinds !== kinds) return false;
    const offset = distanceKm(center, c.center);
    return offset + radiusKm <= c.radiusKm || (c.truncated && offset <= c.radiusKm * SAME_ANSWER_SHARE);
  });
}

async function fetchGroup(
  lang: PhotonLang,
  kinds: PlaceKind[],
  center: LatLng,
  radiusKm: number,
  signal: AbortSignal,
): Promise<boolean> {
  const params = new URLSearchParams({
    lat: center.lat.toFixed(5),
    lon: center.lng.toFixed(5),
    radius: radiusKm.toFixed(1),
    limit: String(PHOTON_LIMIT),
    lang,
  });
  for (const kind of kinds) params.append('osm_tag', `place:${kind}`);

  const res = await fetch(`${PHOTON_REVERSE_URL}?${params}`, { signal });
  if (!res.ok) throw new Error(`Photon HTTP ${res.status}`);
  const json = (await res.json()) as PhotonResponse;
  const features = json.features ?? [];

  let places = cache.get(lang);
  if (!places) {
    places = new Map();
    cache.set(lang, places);
  }
  let added = false;
  let reachedKm = 0;
  for (const feature of features) {
    const props = feature.properties;
    const coords = feature.geometry?.coordinates;
    const kind = kinds.find((k) => k === props?.osm_value);
    if (!props?.name || !coords || !kind) continue;
    // GeoJSON is [lng, lat]
    const location = { lat: coords[1], lng: coords[0] };
    reachedKm = Math.max(reachedKm, distanceKm(center, location));
    const id = `${props.osm_type}${props.osm_id}`;
    if (!places.has(id)) {
      places.set(id, { id, name: props.name, kind, location });
      added = true;
    }
  }

  const truncated = features.length >= PHOTON_LIMIT;
  coverage.push({ lang, kinds: kinds.join(), center, radiusKm: truncated ? reachedKm : radiusKm, truncated });
  return added;
}

/** Places loaded so far, in the given language. */
export function cachedPlaces(lang: Lang): MapPlace[] {
  return [...(cache.get(photonLang(lang))?.values() ?? [])];
}

/** True when `loadPlaces` for this view would have to ask the server. */
export function needsPlaces(center: LatLng, radiusKm: number, zoom: number, lang: Lang): boolean {
  return requestGroups(zoom).some((kinds) => !isCovered(photonLang(lang), kinds.join(), center, radiusKm));
}

/**
 * Load the cities and towns (and, zoomed in, villages) around `center` into
 * the cache, from the free Photon geocoder. Skips what earlier calls already
 * cover. Resolves true when the cache gained places.
 */
export async function loadPlaces(
  center: LatLng,
  radiusKm: number,
  zoom: number,
  lang: Lang,
  signal: AbortSignal,
): Promise<boolean> {
  const pLang = photonLang(lang);
  const groups = requestGroups(zoom).filter((kinds) => !isCovered(pLang, kinds.join(), center, radiusKm));
  const added = await Promise.all(
    groups.map((kinds) => fetchGroup(pLang, kinds, center, radiusKm * RADIUS_SLACK, signal)),
  );
  return added.includes(true);
}

// --- search by name ---------------------------------------------------------

/** `area`: a town, region or country · `landmark`: a beach, peak, island… · `address`: a building, business or street */
export type PlaceMatchKind = 'area' | 'landmark' | 'address';

/** A place found by name, anywhere in the world. */
export interface PlaceMatch {
  id: string;
  name: string;
  /** where it is ("Epirus, Greece"), to tell namesakes apart; may be empty */
  detail: string;
  kind: PlaceMatchKind;
  location: LatLng;
}

/** One answer of Photon's search, as far as this app reads it. */
export interface PhotonSearchFeature {
  properties?: {
    osm_type?: string;
    osm_id?: number;
    /** Photon's own layer: house, street, locality, district, city, county, state, country, other */
    type?: string;
    name?: string;
    housenumber?: string;
    street?: string;
    city?: string;
    county?: string;
    state?: string;
    country?: string;
  };
  geometry?: { coordinates?: [number, number] };
}

const ADDRESS_LAYERS = ['house', 'street'];

function placeMatchKind(layer: string | undefined): PlaceMatchKind {
  if (!layer || layer === 'other') return 'landmark';
  return ADDRESS_LAYERS.includes(layer) ? 'address' : 'area';
}

/**
 * Photon's answers as matches to offer: named, without the repeats OSM holds
 * of one place (a town's point and its boundary), and with towns and
 * landmarks ahead of the businesses and streets named after them.
 */
export function placeMatchesFromPhoton(features: PhotonSearchFeature[]): PlaceMatch[] {
  const seen = new Set<string>();
  const matches: PlaceMatch[] = [];
  for (const feature of features) {
    const props = feature.properties;
    const coords = feature.geometry?.coordinates;
    // an address without a name of its own goes by street and number
    const name = (props?.name || [props?.street, props?.housenumber].filter(Boolean).join(' ')).trim();
    if (!props || !name || !coords || !Number.isFinite(coords[0]) || !Number.isFinite(coords[1])) continue;
    const parts = [props.city, props.county, props.state, props.country].filter(
      (part, i, all): part is string => !!part && part !== name && all.indexOf(part) === i,
    );
    const detail = parts.join(', ');
    const key = `${name}|${detail}`;
    if (seen.has(key)) continue;
    seen.add(key);
    matches.push({
      id: `${props.osm_type}${props.osm_id}`,
      name,
      detail,
      kind: placeMatchKind(props.type),
      // GeoJSON is [lng, lat]
      location: { lat: coords[1], lng: coords[0] },
    });
  }
  const isAddress = (m: PlaceMatch) => m.kind === 'address';
  return [...matches.filter((m) => !isAddress(m)), ...matches.filter(isAddress)];
}

const searchCache = new Map<string, PlaceMatch[]>();

/**
 * Places matching what has been typed so far, best match first; those near
 * `near` count as better matches. Answers are kept for the session, so
 * deleting a letter does not ask again.
 */
export async function searchPlaces(
  query: string,
  lang: Lang,
  near: LatLng | null,
  signal: AbortSignal,
): Promise<PlaceMatch[]> {
  const q = query.trim();
  if (q.length < SEARCH_MIN_LENGTH) return [];
  const params = new URLSearchParams({ q, limit: String(SEARCH_LIMIT), lang: photonLang(lang) });
  if (near) {
    // the bias only needs to know the region
    params.set('lat', near.lat.toFixed(2));
    params.set('lon', near.lng.toFixed(2));
  }
  const key = params.toString();
  const cached = searchCache.get(key);
  if (cached) return cached;

  const res = await fetch(`${PHOTON_SEARCH_URL}?${key}`, { signal });
  if (!res.ok) throw new Error(`Photon HTTP ${res.status}`);
  const json = (await res.json()) as { features?: PhotonSearchFeature[] };
  const matches = placeMatchesFromPhoton(json.features ?? []);
  searchCache.set(key, matches);
  return matches;
}
