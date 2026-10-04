import type { Destination, MainLocation, Trip } from '../types/models';
import { addDays, diffDays, isIsoDate, todayIso } from './dates';
import { haversineDistanceMeters } from './geo';

export const MAX_TRIP_DAYS = 30;
const DEFAULT_TRIP_DAYS = 7;

// auto-plan: consecutive places this close in direction and distance share a day
const SAME_DAY_MAX_BEARING_DEG = 25;
const SAME_DAY_MAX_DISTANCE_M = 40_000;
const SAME_DAY_MAX_STOPS = 2;

/** Last allowed end date for a trip starting on `startDate`. */
export function clampTripEnd(startDate: string, endDate: string): string {
  const days = Math.min(Math.max(diffDays(startDate, endDate), 0), MAX_TRIP_DAYS - 1);
  return addDays(startDate, days);
}

/** Number of days in the trip's date range (1…MAX_TRIP_DAYS). */
export function tripDayCount(trip: Trip): number {
  return Math.min(Math.max(diffDays(trip.startDate, trip.endDate) + 1, 1), MAX_TRIP_DAYS);
}

export function tripDayDate(trip: Trip, dayIndex: number): string {
  return addDays(trip.startDate, dayIndex);
}

/** The plan for exactly the days in the date range (padded with empty days). */
export function visiblePlan(trip: Trip): string[][] {
  return Array.from({ length: tripDayCount(trip) }, (_, i) => trip.plan[i] ?? []);
}

/** destination id → index of the (visible) day it is scheduled on */
export function dayIndexByDestination(trip: Trip): Map<string, number> {
  const dayOf = new Map<string, number>();
  visiblePlan(trip).forEach((ids, day) => ids.forEach((id) => dayOf.set(id, day)));
  return dayOf;
}

/**
 * Move a destination to `dayIndex` (inserted before position `beforeIndex`,
 * or appended), or unschedule it with `dayIndex: null`. A destination is on
 * at most one day, so it is first removed from wherever it was.
 */
export function movePlanStop(
  plan: string[][],
  id: string,
  dayIndex: number | null,
  beforeIndex?: number,
): string[][] {
  let fromDay = -1;
  let fromIndex = -1;
  const next = plan.map((ids, day) => {
    const at = ids.indexOf(id);
    if (at < 0) return ids;
    fromDay = day;
    fromIndex = at;
    return ids.filter((other) => other !== id);
  });
  if (dayIndex === null) return next;
  while (next.length <= dayIndex) next.push([]);
  const target = next[dayIndex];
  let at = beforeIndex ?? target.length;
  // within the same day, removing the item shifted everything after it up
  if (fromDay === dayIndex && fromIndex < at) at -= 1;
  at = Math.max(0, Math.min(at, target.length));
  next[dayIndex] = [...target.slice(0, at), id, ...target.slice(at)];
  return next;
}

/** Drop a deleted destination from every day. */
export function removeFromPlan(plan: string[][], id: string): string[][] {
  return plan.map((ids) => ids.filter((other) => other !== id));
}

/** Compass-style bearing from the home base, in degrees (−180…180). */
function bearingFrom(home: MainLocation, dest: Destination): number {
  return (
    (Math.atan2(dest.location.lng - home.location.lng, dest.location.lat - home.location.lat) * 180) / Math.PI
  );
}

/**
 * Spread the unscheduled destinations over the trip: walk them in order of
 * bearing from home, keep near neighbours on the same day (up to two stops),
 * and otherwise fill the emptiest day. Day 1 is left for arriving unless the
 * trip is only a day or two long.
 */
export function autoPlanTrip(
  trip: Trip,
  home: MainLocation,
  destinations: Destination[],
): { plan: string[][]; count: number } {
  const days = visiblePlan(trip).map((ids) => [...ids]);
  const scheduled = new Set(days.flat());
  const todo = destinations
    .filter((d) => !scheduled.has(d.id))
    .sort((a, b) => bearingFrom(home, a) - bearingFrom(home, b));
  if (todo.length === 0) return { plan: trip.plan, count: 0 };

  const candidates = days.map((_, i) => i).slice(days.length > 2 ? 1 : 0);
  let previous: { dest: Destination; day: number } | null = null;
  for (const dest of todo) {
    const nearPrevious =
      previous !== null &&
      Math.abs(bearingFrom(home, dest) - bearingFrom(home, previous.dest)) < SAME_DAY_MAX_BEARING_DEG &&
      haversineDistanceMeters(previous.dest.location, dest.location) < SAME_DAY_MAX_DISTANCE_M &&
      days[previous.day].length < SAME_DAY_MAX_STOPS;
    const day: number =
      nearPrevious && previous
        ? previous.day
        : candidates.reduce((best, i) => (days[i].length < days[best].length ? i : best), candidates[0]);
    days[day].push(dest.id);
    previous = { dest, day };
  }

  // days beyond the current range stay stored, minus anything just placed
  const placed = new Set(todo.map((d) => d.id));
  const hidden = trip.plan.slice(days.length).map((ids) => ids.filter((id) => !placed.has(id)));
  return { plan: [...days, ...hidden], count: todo.length };
}

/** A week starting today, for data that has no trip yet. */
export function defaultTrip(): Trip {
  const startDate = todayIso();
  return { name: '', startDate, endDate: addDays(startDate, DEFAULT_TRIP_DAYS - 1), plan: [] };
}

/**
 * Make a stored trip safe to use: valid ordered dates within the length
 * limit, and a plan that only references existing destinations, each once.
 */
export function sanitizeTrip(trip: Partial<Trip> | undefined, destinationIds: Set<string>): Trip {
  const fallback = defaultTrip();
  const startDate = isIsoDate(trip?.startDate) ? trip.startDate : fallback.startDate;
  const rawEnd = isIsoDate(trip?.endDate) ? trip.endDate : addDays(startDate, DEFAULT_TRIP_DAYS - 1);
  const seen = new Set<string>();
  const plan = (Array.isArray(trip?.plan) ? trip.plan : []).map((ids) =>
    (Array.isArray(ids) ? ids : []).filter((id) => {
      if (!destinationIds.has(id) || seen.has(id)) return false;
      seen.add(id);
      return true;
    }),
  );
  return {
    name: typeof trip?.name === 'string' ? trip.name : '',
    startDate,
    endDate: clampTripEnd(startDate, rawEnd),
    plan,
  };
}
