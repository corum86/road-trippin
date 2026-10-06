import type { LatLng } from '../types/models';
import type { Lang } from '../i18n/translations';
import { haversineDistanceMeters } from './geo';

const PHOTON_REVERSE_URL = 'https://photon.komoot.io/reverse';
// the public server never returns more than this, nearest first
const PHOTON_LIMIT = 50;
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
