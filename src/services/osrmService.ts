import type { LatLng, RouteInfo } from '../types/models';
import { haversineDistanceMeters } from './geo';

const OSRM_BASE_URL = 'https://router.project-osrm.org/route/v1/driving';
const ASSUMED_FALLBACK_SPEED_KMH = 60;

interface OsrmRouteResponse {
  code: string;
  routes?: Array<{
    distance: number;
    duration: number;
    geometry?: { coordinates: Array<[number, number]> };
  }>;
}

export function estimateFromStraightLine(from: LatLng, to: LatLng): RouteInfo {
  const distanceMeters = haversineDistanceMeters(from, to);
  const durationSeconds = (distanceMeters / 1000 / ASSUMED_FALLBACK_SPEED_KMH) * 3600;
  return {
    distanceMeters,
    durationSeconds,
    source: 'straight-line-estimate',
    fetchedAt: new Date().toISOString(),
    geometry: [
      [from.lat, from.lng],
      [to.lat, to.lng],
    ],
  };
}

export async function fetchRoute(from: LatLng, to: LatLng): Promise<RouteInfo> {
  const url = `${OSRM_BASE_URL}/${from.lng},${from.lat};${to.lng},${to.lat}?overview=full&geometries=geojson`;

  try {
    const res = await fetch(url);
    if (!res.ok) throw new Error(`OSRM HTTP ${res.status}`);
    const json = (await res.json()) as OsrmRouteResponse;
    const route = json.routes?.[0];
    if (json.code !== 'Ok' || !route) throw new Error('OSRM: no route found');
    return {
      distanceMeters: route.distance,
      durationSeconds: route.duration,
      source: 'osrm',
      fetchedAt: new Date().toISOString(),
      // GeoJSON is [lng, lat]; Leaflet wants [lat, lng]
      geometry: route.geometry?.coordinates.map(([lng, lat]) => [lat, lng] as [number, number]),
    };
  } catch {
    return estimateFromStraightLine(from, to);
  }
}

const OSRM_TABLE_URL = 'https://router.project-osrm.org/table/v1/driving';

export interface DriveFromHome {
  minutes: number;
  km: number;
  /** straight-line estimate because routing failed for this point */
  estimated: boolean;
}

interface OsrmTableResponse {
  code: string;
  durations?: Array<Array<number | null>>;
  distances?: Array<Array<number | null>>;
}

/**
 * Drive time and distance from `from` to every point, in one OSRM table
 * request (the public server asks for few, batched calls). Points it can't
 * route fall back to a straight-line estimate.
 */
export async function fetchDrivesFrom(from: LatLng, points: LatLng[]): Promise<DriveFromHome[]> {
  const estimate = (to: LatLng): DriveFromHome => {
    const info = estimateFromStraightLine(from, to);
    return { minutes: info.durationSeconds / 60, km: info.distanceMeters / 1000, estimated: true };
  };
  if (points.length === 0) return [];
  const coords = [from, ...points].map((p) => `${p.lng},${p.lat}`).join(';');
  try {
    const res = await fetch(`${OSRM_TABLE_URL}/${coords}?sources=0&annotations=duration,distance`);
    if (!res.ok) throw new Error(`OSRM HTTP ${res.status}`);
    const json = (await res.json()) as OsrmTableResponse;
    const durations = json.durations?.[0];
    const distances = json.distances?.[0];
    if (json.code !== 'Ok' || !durations) throw new Error('OSRM: no table');
    return points.map((point, i) => {
      // index 0 is the origin itself
      const seconds = durations[i + 1];
      const meters = distances?.[i + 1];
      if (seconds == null) return estimate(point);
      return {
        minutes: seconds / 60,
        km: meters != null ? meters / 1000 : estimate(point).km,
        estimated: false,
      };
    });
  } catch {
    return points.map(estimate);
  }
}
