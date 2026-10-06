import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { v4 as uuidv4 } from 'uuid';
import {
  CURRENT_DATA_VERSION,
  type Destination,
  type DestinationDraft,
  type PlannedTrip,
  type LatLng,
  type MainLocation,
  type Photo,
  type Rating,
  type RouteInfo,
  type Trip,
  type TripStatus,
  type VacationMapData,
  type VisitLog,
} from '../types/models';
import { diffDays, isIsoDate, todayIso } from '../services/dates';
import {
  clampTripEnd,
  emptyTrip,
  movePlanStop,
  removeFromPlan,
  replanTrip,
  sanitizeTrip,
  scheduleUnscheduled,
  swapPlanStop,
  togglePlanDay,
  tripDayCount,
  type StopRef,
} from '../services/tripPlan';

const DATA_URL = '/data/vacation-data.json';

function isValidVacationMapData(value: unknown): value is VacationMapData {
  if (!value || typeof value !== 'object') return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v.version === 'number' &&
    // an object, or null on a map with no home base yet
    typeof v.mainLocation === 'object' &&
    Array.isArray(v.destinations)
  );
}

/** v2 stored the trip as dated itinerary days plus a separate trip name. */
interface LegacyTripFields {
  tripName?: string;
  itinerary?: Array<{ date?: string; stopIds?: string[] }>;
}

function tripFromLegacy(legacy: LegacyTripFields): Partial<Trip> | undefined {
  const days = (legacy.itinerary ?? []).filter((day) => isIsoDate(day.date)) as Array<{
    date: string;
    stopIds?: string[];
  }>;
  if (days.length === 0) return legacy.tripName ? { name: legacy.tripName } : undefined;
  const dates = days.map((day) => day.date).sort();
  const startDate = dates[0];
  const plan: string[][] = [];
  for (const day of days) {
    const index = diffDays(startDate, day.date);
    plan[index] = [...(plan[index] ?? []), ...(day.stopIds ?? [])];
  }
  return {
    name: legacy.tripName ?? '',
    startDate,
    endDate: dates[dates.length - 1],
    // fill the gaps a sparse assignment leaves
    plan: Array.from(plan, (ids) => ids ?? []),
  };
}

/**
 * Bring data of any earlier version up to the current shape. Idempotent, so
 * it is safe to run on every entry point: seed fetch, localStorage
 * rehydration and file import.
 */
export function migrateVacationMapData(raw: VacationMapData): VacationMapData {
  // older data lacks the newer fields entirely; read through a loose view of it
  const loose = raw as Partial<VacationMapData> & LegacyTripFields;
  const destinations: Destination[] = raw.destinations.map((d) => {
    const dest = d as Partial<Destination> & Pick<Destination, 'id' | 'name' | 'location'>;
    return {
      ...dest,
      attractions: dest.attractions ?? [],
      photos: dest.photos ?? [],
      links: dest.links ?? [],
      status: dest.status === 'visited' ? 'visited' : 'planned',
      favorite: dest.favorite === true,
      visit: dest.visit ? { ...dest.visit, photos: dest.visit.photos ?? [] } : undefined,
    };
  });
  return {
    version: CURRENT_DATA_VERSION,
    mainLocation: raw.mainLocation ?? null,
    destinations,
    trip: sanitizeTrip(loose.trip ?? tripFromLegacy(loose), new Set(destinations.map((d) => d.id))),
  };
}

function sameLocation(a: LatLng, b: LatLng): boolean {
  return a.lat === b.lat && a.lng === b.lng;
}

function normalizeName(name: string): string {
  return name.trim().toLowerCase();
}

/** `existing` plus the items of `added` it doesn't have yet (compared by `key`). */
function unionBy<T>(existing: T[], added: T[], key: (item: T) => string): T[] {
  const seen = new Set(existing.map(key));
  return [...existing, ...added.filter((item) => !seen.has(key(item)))];
}

function ensureVisit(dest: Destination): VisitLog {
  return dest.visit ?? { photos: [] };
}

interface MapDataState {
  data: VacationMapData | null;
  selectedDestinationId: string | null;
  isLoaded: boolean;
  loadError: string | null;
  _loadStarted: boolean;

  loadInitialData: () => Promise<void>;
  setMainLocation: (loc: MainLocation) => void;
  /** save a new place; pass `id` to keep a known one (e.g. a planner suggestion's) */
  addDestination: (dest: DestinationDraft & Partial<Pick<Destination, 'id' | 'status' | 'favorite' | 'visit'>>) => string;
  updateDestination: (id: string, patch: Partial<Omit<Destination, 'id'>>) => void;
  removeDestination: (id: string) => void;
  setSelectedDestination: (id: string | null) => void;
  setRouteInfo: (destinationId: string, info: RouteInfo) => void;
  setStatus: (id: string, status: TripStatus) => void;
  toggleFavorite: (id: string) => void;
  setRating: (id: string, rating: Rating) => void;
  addVisitPhoto: (id: string, photo: Photo) => void;
  removeVisitPhoto: (id: string, photoId: string) => void;
  updateVisit: (id: string, patch: Partial<VisitLog>) => void;
  setTripName: (name: string) => void;
  /** delete the trip (name, dates, plan, planner answers), leaving an empty one; the places stay */
  clearTrip: () => void;
  /** set the trip's date range (end is clamped to the maximum trip length) */
  setTripDates: (startDate: string, endDate: string) => void;
  /** move one visit to a day (before position `beforeIndex`, or last), or off its day with null */
  moveStop: (from: StopRef, dayIndex: number | null, beforeIndex?: number) => void;
  /** add a visit to a day, or remove it if the place is already on that day */
  toggleTripDay: (id: string, dayIndex: number) => void;
  /** replace the stop at `dayIndex`/`index` with another place */
  swapStop: (dayIndex: number, index: number, newId: string) => void;
  /** plan the places that are on no day; returns how many were placed */
  autoPlan: () => number;
  /** re-plan days from `fromDay` on by drive time; returns how many places were redistributed */
  replan: (fromDay: number, includeUnscheduled: boolean) => number;
  /** replace the whole plan, e.g. to undo an auto-plan */
  setPlan: (plan: string[][]) => void;
  /** save the trip-planner result: merge its places into Places and replace the trip */
  applyPlannedTrip: (planned: PlannedTrip) => void;
  replaceAllData: (data: VacationMapData) => void;
  /** delete everything: the places, the trip and the home base */
  clearAllData: () => void;
}

export const useMapDataStore = create<MapDataState>()(
  persist(
    (set, get) => {
      function updateTrip(fn: (trip: Trip) => Trip) {
        const current = get().data;
        if (!current) return;
        set({ data: { ...current, trip: fn(current.trip) } });
      }

      function mapDestination(id: string, fn: (d: Destination) => Destination) {
        const current = get().data;
        if (!current) return;
        set({
          data: {
            ...current,
            destinations: current.destinations.map((d) => (d.id === id ? fn(d) : d)),
          },
        });
      }

      return {
        data: null,
        selectedDestinationId: null,
        isLoaded: false,
        loadError: null,
        _loadStarted: false,

        loadInitialData: async () => {
          if (get().data || get()._loadStarted) {
            if (get().data) set({ isLoaded: true });
            return;
          }
          set({ _loadStarted: true });
          try {
            const res = await fetch(DATA_URL);
            if (!res.ok) throw new Error(`Failed to fetch seed data: HTTP ${res.status}`);
            const json = await res.json();
            if (!isValidVacationMapData(json)) throw new Error('Seed data has an invalid shape');
            // cloud sync may have delivered the saved map while the seed was on its way
            if (get().data) set({ isLoaded: true });
            else set({ data: migrateVacationMapData(json), isLoaded: true, loadError: null });
          } catch (err) {
            set({
              isLoaded: true,
              loadError: err instanceof Error ? err.message : 'Failed to load vacation data',
            });
          }
        },

        setMainLocation: (loc) => {
          const current = get().data;
          if (!current) return;
          // every cached route starts at the home base, so moving it voids them all
          const moved = !current.mainLocation || !sameLocation(current.mainLocation.location, loc.location);
          set({
            data: {
              ...current,
              mainLocation: loc,
              destinations: moved
                ? current.destinations.map((d) => ({ ...d, routeInfo: undefined }))
                : current.destinations,
            },
          });
        },

        addDestination: (dest) => {
          const current = get().data;
          const id = dest.id ?? uuidv4();
          if (!current) return id;
          const created: Destination = { status: 'planned', favorite: false, ...dest, id };
          set({
            data: { ...current, destinations: [...current.destinations, created] },
          });
          return id;
        },

        updateDestination: (id, patch) =>
          mapDestination(id, (d) => {
            const next = { ...d, ...patch };
            // a moved destination needs a fresh route from OSRM
            return sameLocation(d.location, next.location) ? next : { ...next, routeInfo: undefined };
          }),

        removeDestination: (id) => {
          const current = get().data;
          if (!current) return;
          set({
            data: {
              ...current,
              destinations: current.destinations.filter((d) => d.id !== id),
              trip: { ...current.trip, plan: removeFromPlan(current.trip.plan, id) },
            },
            selectedDestinationId: get().selectedDestinationId === id ? null : get().selectedDestinationId,
          });
        },

        setSelectedDestination: (id) => set({ selectedDestinationId: id }),

        setRouteInfo: (destinationId, info) =>
          mapDestination(destinationId, (d) => ({ ...d, routeInfo: info })),

        setStatus: (id, status) =>
          mapDestination(id, (d) => {
            if (status === 'planned') return { ...d, status };
            // switching back to planned keeps the log, so only stamp a date when there is none
            const visit = ensureVisit(d);
            return { ...d, status, visit: { ...visit, visitedOn: visit.visitedOn ?? todayIso() } };
          }),

        toggleFavorite: (id) => mapDestination(id, (d) => ({ ...d, favorite: !d.favorite })),

        setRating: (id, rating) =>
          mapDestination(id, (d) => ({ ...d, visit: { ...ensureVisit(d), rating } })),

        addVisitPhoto: (id, photo) =>
          mapDestination(id, (d) => {
            const visit = ensureVisit(d);
            return { ...d, visit: { ...visit, photos: [...visit.photos, photo] } };
          }),

        removeVisitPhoto: (id, photoId) =>
          mapDestination(id, (d) => {
            const visit = ensureVisit(d);
            return { ...d, visit: { ...visit, photos: visit.photos.filter((p) => p.id !== photoId) } };
          }),

        updateVisit: (id, patch) =>
          mapDestination(id, (d) => ({ ...d, visit: { ...ensureVisit(d), ...patch } })),

        setTripName: (name) => updateTrip((trip) => ({ ...trip, name })),

        clearTrip: () => updateTrip(() => emptyTrip()),

        setTripDates: (startDate, endDate) =>
          updateTrip((trip) => {
            const next = { ...trip, startDate, endDate: clampTripEnd(startDate, endDate) };
            // days are derived from the range; the plan only ever grows, so
            // shortening and re-extending the trip brings its stops back
            const plan = Array.from(
              { length: Math.max(trip.plan.length, tripDayCount(next)) },
              (_, i) => trip.plan[i] ?? [],
            );
            return { ...next, plan };
          }),

        moveStop: (from, dayIndex, beforeIndex) =>
          updateTrip((trip) => ({ ...trip, plan: movePlanStop(trip.plan, from, dayIndex, beforeIndex) })),

        toggleTripDay: (id, dayIndex) => updateTrip((trip) => ({ ...trip, plan: togglePlanDay(trip.plan, id, dayIndex) })),

        swapStop: (dayIndex, index, newId) =>
          updateTrip((trip) => ({ ...trip, plan: swapPlanStop(trip.plan, dayIndex, index, newId) })),

        autoPlan: () => {
          const current = get().data;
          // planning goes by drive time from the home base
          if (!current?.mainLocation) return 0;
          const { plan, count } = scheduleUnscheduled(current.trip, current.mainLocation, current.destinations);
          if (count > 0) set({ data: { ...current, trip: { ...current.trip, plan } } });
          return count;
        },

        replan: (fromDay, includeUnscheduled) => {
          const current = get().data;
          if (!current?.mainLocation) return 0;
          const { plan, count } = replanTrip(
            current.trip,
            current.mainLocation,
            current.destinations,
            fromDay,
            includeUnscheduled,
          );
          if (count > 0) set({ data: { ...current, trip: { ...current.trip, plan } } });
          return count;
        },

        setPlan: (plan) => updateTrip((trip) => ({ ...trip, plan })),

        applyPlannedTrip: (planned) => {
          const current = get().data;
          if (!current) return;
          const destinations = [...current.destinations];
          // a planner place may already be saved: under its id, or by name
          const idMap = new Map<string, string>();
          for (const place of planned.places) {
            const at = destinations.findIndex(
              (d) => d.id === place.id || normalizeName(d.name) === normalizeName(place.name),
            );
            if (at >= 0) {
              const existing = destinations[at];
              idMap.set(place.id, existing.id);
              // keep the visit log, favourite and status; add what's new
              destinations[at] = {
                ...existing,
                photos: unionBy(existing.photos, place.photos, (p) => p.url),
                links: unionBy(existing.links, place.links, (l) => l.url),
                attractions: unionBy(existing.attractions, place.attractions, (a) => a.trim().toLowerCase()),
              };
            } else {
              idMap.set(place.id, place.id);
              destinations.push({
                id: place.id,
                name: place.name,
                location: place.location,
                attractions: place.attractions,
                photos: place.photos,
                links: place.links,
                notes: place.notes,
                routeInfo: place.routeInfo,
                status: 'planned',
                favorite: false,
              });
            }
          }
          const plan = planned.plan.map((ids) => [...new Set(ids.map((id) => idMap.get(id) ?? id))]);
          set({
            data: {
              ...current,
              destinations,
              trip: {
                name: planned.name,
                startDate: planned.startDate,
                endDate: clampTripEnd(planned.startDate, planned.endDate),
                plan,
                preferences: planned.preferences,
                suggestions: planned.suggestions,
              },
            },
            selectedDestinationId: null,
          });
        },

        replaceAllData: (data) =>
          set({ data: migrateVacationMapData(data), selectedDestinationId: null, loadError: null }),

        clearAllData: () => {
          const current = get().data;
          if (!current) return;
          set({
            data: {
              version: CURRENT_DATA_VERSION,
              mainLocation: null,
              destinations: [],
              trip: emptyTrip(),
            },
            selectedDestinationId: null,
            loadError: null,
          });
        },
      };
    },
    {
      name: 'vacation-map-data',
      partialize: (state) => ({ data: state.data }),
      // data saved by an older build is migrated on the way in
      merge: (persisted, current) => {
        const saved = persisted as Partial<MapDataState> | undefined;
        return {
          ...current,
          ...saved,
          data: saved?.data ? migrateVacationMapData(saved.data) : current.data,
        };
      },
    },
  ),
);
