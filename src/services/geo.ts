import type { LatLng } from '../types/models';

const EARTH_RADIUS_METERS = 6371000;

function toRadians(deg: number): number {
  return (deg * Math.PI) / 180;
}

export function haversineDistanceMeters(a: LatLng, b: LatLng): number {
  const dLat = toRadians(b.lat - a.lat);
  const dLng = toRadians(b.lng - a.lng);
  const lat1 = toRadians(a.lat);
  const lat2 = toRadians(b.lat);

  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2;
  const c = 2 * Math.asin(Math.min(1, Math.sqrt(h)));
  return EARTH_RADIUS_METERS * c;
}

export interface Point {
  x: number;
  y: number;
}

/** Small deterministic string hash (djb2) for stable per-id variation. */
export function hashString(s: string): number {
  let h = 5381;
  for (let i = 0; i < s.length; i++) {
    h = ((h << 5) + h + s.charCodeAt(i)) | 0;
  }
  return Math.abs(h);
}

/**
 * Control point for a quadratic bezier curve from `from` to `to`, offset
 * perpendicular to the midpoint so the curve bows outward instead of
 * drawing a straight line. A negative `bow` bends to the opposite side.
 */
export function bezierControlPoint(from: Point, to: Point, bow = 0.22): Point {
  const mx = (from.x + to.x) / 2;
  const my = (from.y + to.y) / 2;
  const dx = to.x - from.x;
  const dy = to.y - from.y;
  // perpendicular vector
  const px = -dy;
  const py = dx;
  return { x: mx + px * bow, y: my + py * bow };
}

/**
 * Shorten a quadratic bezier so it ends roughly `gap` before its endpoint
 * (de Casteljau subdivision at t). Keeps at least half the curve so very
 * short arrows don't collapse. Returns the trimmed control and end points;
 * the start point is unchanged.
 */
export function trimQuadraticBezier(
  from: Point,
  control: Point,
  to: Point,
  gap: number,
): { control: Point; end: Point } {
  const dist = Math.hypot(to.x - from.x, to.y - from.y);
  const t = Math.max(0.5, 1 - gap / Math.max(dist, 1));
  const at = (a: number, b: number, c: number) =>
    (1 - t) * (1 - t) * a + 2 * (1 - t) * t * b + t * t * c;
  return {
    control: {
      x: from.x + (control.x - from.x) * t,
      y: from.y + (control.y - from.y) * t,
    },
    end: { x: at(from.x, control.x, to.x), y: at(from.y, control.y, to.y) },
  };
}
