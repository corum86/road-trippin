import type { LatLng, RouteInfo } from '../types/models';
import { haversineDistanceMeters } from './geo';

const OSRM_BASE_URL = 'https://router.project-osrm.org/route/v1/driving';
const ASSUMED_FALLBACK_SPEED_KMH = 60;

interface OsrmRouteResponse {
  code: string;
  routes?: Array<{ distance: number; duration: number }>;
}

function estimateFromStraightLine(from: LatLng, to: LatLng): RouteInfo {
  const distanceMeters = haversineDistanceMeters(from, to);
  const durationSeconds = (distanceMeters / 1000 / ASSUMED_FALLBACK_SPEED_KMH) * 3600;
  return {
    distanceMeters,
    durationSeconds,
    source: 'straight-line-estimate',
    fetchedAt: new Date().toISOString(),
  };
}

export async function fetchRoute(from: LatLng, to: LatLng): Promise<RouteInfo> {
  const url = `${OSRM_BASE_URL}/${from.lng},${from.lat};${to.lng},${to.lat}?overview=false`;

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
    };
  } catch {
    return estimateFromStraightLine(from, to);
  }
}
