import type {
  BudgetLevel,
  Destination,
  LatLng,
  MainLocation,
  MustHave,
  PlaceSuggestion,
  TravelGroup,
  TravelStyle,
  Trip,
  TripPreferences,
} from '../types/models';
import { addDays, diffDays, isIsoDate, todayIso } from './dates';
import { haversineDistanceMeters } from './geo';
import { estimateFromStraightLine } from './osrmService';

export const MAX_TRIP_DAYS = 30;
const DEFAULT_TRIP_DAYS = 7;

/** Last allowed end date for a trip starting on `startDate`. */
export function clampTripEnd(startDate: string, endDate: string): string {
  const days = Math.min(Math.max(diffDays(startDate, endDate), 0), MAX_TRIP_DAYS - 1);
  return addDays(startDate, days);
}

/** Number of days in the trip's date range (1…MAX_TRIP_DAYS); 0 while the trip has no dates. */
export function tripDayCount(trip: Pick<Trip, 'startDate' | 'endDate'>): number {
  if (!trip.startDate || !trip.endDate) return 0;
  return Math.min(Math.max(diffDays(trip.startDate, trip.endDate) + 1, 1), MAX_TRIP_DAYS);
}

/** The date of day `dayIndex`; empty while the trip has no dates (and so no days). */
export function tripDayDate(trip: Pick<Trip, 'startDate'>, dayIndex: number): string {
  return trip.startDate ? addDays(trip.startDate, dayIndex) : '';
}

/** The plan for exactly the days in the date range (padded with empty days). */
export function visiblePlan(trip: Trip): string[][] {
  return Array.from({ length: tripDayCount(trip) }, (_, i) => trip.plan[i] ?? []);
}

/** destination id → the (visible) days it is on, in order; more than one is a revisit */
export function daysByDestination(trip: Trip): Map<string, number[]> {
  const days = new Map<string, number[]>();
  visiblePlan(trip).forEach((ids, day) =>
    ids.forEach((id) => days.set(id, [...(days.get(id) ?? []), day])),
  );
  return days;
}

/** Saved places on no day of the trip. */
export function unscheduledDestinations(trip: Trip, destinations: Destination[]): Destination[] {
  const scheduled = new Set(visiblePlan(trip).flat());
  return destinations.filter((d) => !scheduled.has(d.id));
}

/** One visit: a stop on a given day, or a place picked from the unscheduled tray. */
export type StopRef = { id: string; day: number; index: number } | { id: string; day: null };

/**
 * Move one visit to `toDay` (before position `beforeIndex`, or last), or take
 * it off its day with `toDay: null`. Only that visit moves; other days with
 * the same place are untouched. Moving onto a day that already has the place
 * changes nothing.
 */
export function movePlanStop(
  plan: string[][],
  from: StopRef,
  toDay: number | null,
  beforeIndex?: number,
): string[][] {
  if (toDay !== null && toDay !== from.day && plan[toDay]?.includes(from.id)) return plan;
  const next = plan.map((ids) => [...ids]);
  let insertAt = beforeIndex;
  if (from.day !== null) {
    next[from.day]?.splice(from.index, 1);
    // within the same day, removing the item shifted everything after it up
    if (toDay === from.day && insertAt !== undefined && insertAt > from.index) insertAt -= 1;
  }
  if (toDay === null) return next;
  while (next.length <= toDay) next.push([]);
  const target = next[toDay];
  const at = Math.max(0, Math.min(insertAt ?? target.length, target.length));
  target.splice(at, 0, from.id);
  return next;
}

/** Add a visit to `day`, or remove it when the place is already on that day. */
export function togglePlanDay(plan: string[][], id: string, day: number): string[][] {
  const next = plan.map((ids) => [...ids]);
  while (next.length <= day) next.push([]);
  const at = next[day].indexOf(id);
  if (at >= 0) next[day].splice(at, 1);
  else next[day].push(id);
  return next;
}

/** Put `newId` in place of the stop at `day`/`index` (a no-op if that day already has it). */
export function swapPlanStop(plan: string[][], day: number, index: number, newId: string): string[][] {
  if (!plan[day] || plan[day].includes(newId)) return plan;
  return plan.map((ids, d) => (d === day ? ids.map((id, i) => (i === index ? newId : id)) : ids));
}

/** Drop a deleted destination from every day. */
export function removeFromPlan(plan: string[][], id: string): string[][] {
  return plan.map((ids) => ids.filter((other) => other !== id));
}

// ---------------------------------------------------------------------------
// Distance planner

/** What the planner needs to know about a place. */
export interface PlanItem {
  id: string;
  location: LatLng;
  /** one-way drive from the home base */
  driveMinutes: number;
}

// neighbours in the same direction share a day, up to two per day
const CLUSTER_MAX_BEARING_DEG = 25;
const CLUSTER_MAX_DISTANCE_M = 40_000;
const CLUSTER_MAX_SIZE = 2;
// a drive this long fills a day on its own
const LONG_DRIVE_MINUTES = 100;
// extra time a day's second (third…) stop adds to the round trip
const MINUTES_PER_EXTRA_STOP = 20;

/** Compass-style bearing from the home base, in degrees (−180…180). */
function bearingFrom(home: LatLng, point: LatLng): number {
  return (Math.atan2(point.lng - home.lng, point.lat - home.lat) * 180) / Math.PI;
}

/** Drive time from home: the cached OSRM route, else a straight-line estimate. */
export function destinationDriveMinutes(dest: Destination, home: MainLocation): number {
  const route = dest.routeInfo ?? estimateFromStraightLine(home.location, dest.location);
  return route.durationSeconds / 60;
}

export function toPlanItem(dest: Destination, home: MainLocation): PlanItem {
  return { id: dest.id, location: dest.location, driveMinutes: destinationDriveMinutes(dest, home) };
}

/**
 * Plan `items` onto days `fromDay…dayCount-1` of `base` (earlier days are
 * never touched). Places are walked in order of bearing from home and
 * grouped with close neighbours; long drives stay alone. When there are
 * enough empty days, groups are spread evenly so busy days alternate with
 * free ones; otherwise each group goes to the least-loaded day.
 */
export function planDays(
  items: PlanItem[],
  dayCount: number,
  home: LatLng,
  base: string[][] | null,
  fromDay = 0,
): string[][] {
  const plan = Array.from({ length: dayCount }, (_, i) => [...(base?.[i] ?? [])]);
  const sorted = [...items].sort((a, b) => bearingFrom(home, a.location) - bearingFrom(home, b.location));

  const clusters: PlanItem[][] = [];
  for (const item of sorted) {
    const current = clusters[clusters.length - 1];
    const last = current?.[current.length - 1];
    const joins =
      !!current &&
      !!last &&
      current.length < CLUSTER_MAX_SIZE &&
      item.driveMinutes < LONG_DRIVE_MINUTES &&
      last.driveMinutes < LONG_DRIVE_MINUTES &&
      Math.abs(bearingFrom(home, item.location) - bearingFrom(home, last.location)) < CLUSTER_MAX_BEARING_DEG &&
      haversineDistanceMeters(item.location, last.location) < CLUSTER_MAX_DISTANCE_M;
    if (joins) current.push(item);
    else clusters.push([item]);
  }

  const start = Math.min(Math.max(fromDay, 0), dayCount - 1);
  let candidates = Array.from({ length: dayCount - start }, (_, i) => start + i);
  // on a fresh plan, day 1 is for arriving, if there is room elsewhere
  if (start === 0 && dayCount > 2 && clusters.length < dayCount) candidates = candidates.slice(1);
  if (candidates.length === 0) candidates = [dayCount - 1];

  const place = (cluster: PlanItem[], day: number) =>
    cluster.forEach((item) => {
      if (!plan[day].includes(item.id)) plan[day].push(item.id);
    });
  const emptyDays = candidates.filter((day) => plan[day].length === 0);
  if (clusters.length > 0 && clusters.length <= emptyDays.length) {
    clusters.forEach((cluster, i) => place(cluster, emptyDays[Math.floor((i * emptyDays.length) / clusters.length)]));
  } else {
    for (const cluster of clusters) {
      const day = candidates.reduce((best, d) => (plan[d].length < plan[best].length ? d : best), candidates[0]);
      place(cluster, day);
    }
  }
  return plan;
}

/** Rough driving for a day: there and back to the furthest stop, plus time per extra stop. */
export function dayDriveMinutes(stopDriveMinutes: number[]): number {
  if (stopDriveMinutes.length === 0) return 0;
  return 2 * Math.max(...stopDriveMinutes) + MINUTES_PER_EXTRA_STOP * (stopDriveMinutes.length - 1);
}

/** Schedule only the places that are on no day ("Auto-plan"). */
export function scheduleUnscheduled(
  trip: Trip,
  home: MainLocation,
  destinations: Destination[],
): { plan: string[][]; count: number } {
  const todo = unscheduledDestinations(trip, destinations);
  const visible = visiblePlan(trip);
  if (todo.length === 0 || visible.length === 0) return { plan: trip.plan, count: 0 };
  const next = planDays(todo.map((d) => toPlanItem(d, home)), visible.length, home.location, visible, 0);
  return { plan: [...next, ...trip.plan.slice(visible.length)], count: todo.length };
}

/** First day that still has a place to visit (re-plan defaults to it). */
export function firstOpenDay(trip: Trip, destinations: Destination[]): number {
  const byId = new Map(destinations.map((d) => [d.id, d]));
  const day = visiblePlan(trip).findIndex((ids) => ids.some((id) => byId.get(id)?.status !== 'visited'));
  return Math.max(day, 0);
}

/**
 * The places a re-plan from `fromDay` redistributes: unvisited stops on those
 * days (one entry per place, so revisits collapse into one visit) plus,
 * optionally, unvisited places that are on no day.
 */
export function replanPool(
  trip: Trip,
  destinations: Destination[],
  fromDay: number,
  includeUnscheduled: boolean,
): Destination[] {
  const byId = new Map(destinations.map((d) => [d.id, d]));
  const visible = visiblePlan(trip);
  const pool = new Map<string, Destination>();
  for (const ids of visible.slice(fromDay)) {
    for (const id of ids) {
      const dest = byId.get(id);
      if (dest && dest.status !== 'visited') pool.set(id, dest);
    }
  }
  if (includeUnscheduled) {
    for (const dest of unscheduledDestinations(trip, destinations)) {
      if (dest.status !== 'visited') pool.set(dest.id, dest);
    }
  }
  return [...pool.values()];
}

/**
 * Re-plan days `fromDay…end`: earlier days stay as they are, visited stops
 * stay put, and the rest of the pool is planned again by drive time.
 */
export function replanTrip(
  trip: Trip,
  home: MainLocation,
  destinations: Destination[],
  fromDay: number,
  includeUnscheduled: boolean,
): { plan: string[][]; count: number } {
  const byId = new Map(destinations.map((d) => [d.id, d]));
  const visible = visiblePlan(trip);
  if (visible.length === 0) return { plan: trip.plan, count: 0 };
  const from = Math.min(Math.max(fromDay, 0), visible.length - 1);
  const pool = replanPool(trip, destinations, from, includeUnscheduled);
  const keep = visible.map((ids, day) =>
    day < from ? ids : ids.filter((id) => byId.get(id)?.status === 'visited'),
  );
  const next = planDays(pool.map((d) => toPlanItem(d, home)), visible.length, home.location, keep, from);
  return { plan: [...next, ...trip.plan.slice(visible.length)], count: pool.length };
}

// ---------------------------------------------------------------------------
// Defaults and validation

/** A cleared trip: no name, no dates, no plan. */
export function emptyTrip(): Trip {
  return { name: '', startDate: null, endDate: null, plan: [] };
}

const GROUPS: TravelGroup[] = ['couple', 'family', 'friends'];
const STYLES: TravelStyle[] = ['relaxed', 'active', 'sightseeing', 'culture', 'food', 'nature'];
const MUST_HAVES: MustHave[] = ['beach', 'food', 'kids', 'nightlife'];

function oneOf<T extends string>(allowed: readonly T[], value: unknown): value is T {
  return typeof value === 'string' && (allowed as readonly string[]).includes(value);
}

function listOf<T extends string>(allowed: readonly T[], value: unknown): T[] {
  return Array.isArray(value) ? [...new Set(value.filter((v): v is T => oneOf(allowed, v)))] : [];
}

function asBudget(value: unknown): BudgetLevel | null {
  return value === 1 || value === 2 || value === 3 ? value : null;
}

/** Keep only well-formed planner answers. */
export function sanitizePreferences(value: unknown): TripPreferences | undefined {
  if (!value || typeof value !== 'object') return undefined;
  const p = value as Record<string, unknown>;
  if (!oneOf(GROUPS, p.group)) return undefined;
  return {
    maxDriveMinutes: typeof p.maxDriveMinutes === 'number' && p.maxDriveMinutes > 0 ? p.maxDriveMinutes : null,
    ferry: p.ferry !== false,
    group: p.group,
    styles: listOf(STYLES, p.styles),
    budget: asBudget(p.budget),
    mustHaves: listOf(MUST_HAVES, p.mustHaves),
  };
}

/** Keep only well-formed suggestions (they come from an AI model). */
export function sanitizeSuggestions(value: unknown): PlaceSuggestion[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item): PlaceSuggestion[] => {
    if (!item || typeof item !== 'object') return [];
    const s = item as Record<string, unknown>;
    const location = s.location as Partial<LatLng> | undefined;
    if (
      typeof s.id !== 'string' ||
      typeof s.name !== 'string' ||
      !s.name.trim() ||
      typeof location?.lat !== 'number' ||
      typeof location.lng !== 'number' ||
      !Number.isFinite(location.lat) ||
      !Number.isFinite(location.lng)
    ) {
      return [];
    }
    const driveMinutes = typeof s.driveMinutes === 'number' && s.driveMinutes >= 0 ? s.driveMinutes : 0;
    return [
      {
        id: s.id,
        name: s.name.trim(),
        location: { lat: location.lat, lng: location.lng },
        blurb: typeof s.blurb === 'string' ? s.blurb : '',
        styles: listOf(STYLES, s.styles),
        groups: listOf(GROUPS, s.groups),
        budget: asBudget(s.budget) ?? 2,
        mustHaves: listOf(MUST_HAVES, s.mustHaves),
        ferry: s.ferry === true,
        driveMinutes,
        distanceKm: typeof s.distanceKm === 'number' && s.distanceKm >= 0 ? s.distanceKm : 0,
        estimated: s.estimated === true,
      },
    ];
  });
}

/**
 * Make a stored trip safe to use: valid ordered dates within the length
 * limit, and a plan that only references existing destinations, at most
 * once per day. Dates stored as null stay null (the traveller cleared them);
 * missing or broken ones, as in data that has no trip yet, become a week
 * starting today.
 */
export function sanitizeTrip(trip: Partial<Trip> | undefined, destinationIds: Set<string>): Trip {
  const undated = trip?.startDate === null;
  const startDate = isIsoDate(trip?.startDate) ? trip.startDate : todayIso();
  const rawEnd = isIsoDate(trip?.endDate) ? trip.endDate : addDays(startDate, DEFAULT_TRIP_DAYS - 1);
  const plan = (Array.isArray(trip?.plan) ? trip.plan : []).map((ids) => [
    ...new Set((Array.isArray(ids) ? ids : []).filter((id) => destinationIds.has(id))),
  ]);
  const preferences = sanitizePreferences(trip?.preferences);
  const suggestions = sanitizeSuggestions(trip?.suggestions);
  return {
    name: typeof trip?.name === 'string' ? trip.name : '',
    startDate: undated ? null : startDate,
    endDate: undated ? null : clampTripEnd(startDate, rawEnd),
    plan,
    ...(preferences ? { preferences } : {}),
    ...(suggestions.length > 0 ? { suggestions } : {}),
  };
}
