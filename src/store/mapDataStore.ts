import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { v4 as uuidv4 } from 'uuid';
import {
  CURRENT_DATA_VERSION,
  type Destination,
  type DestinationDraft,
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
  autoPlanTrip,
  clampTripEnd,
  movePlanStop,
  removeFromPlan,
  sanitizeTrip,
  tripDayCount,
} from '../services/tripPlan';

const DATA_URL = '/data/vacation-data.json';

function isValidVacationMapData(value: unknown): value is VacationMapData {
  if (!value || typeof value !== 'object') return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v.version === 'number' &&
    !!v.mainLocation &&
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
 * rehydration, file import and reset.
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
    mainLocation: raw.mainLocation,
    destinations,
    trip: sanitizeTrip(loose.trip ?? tripFromLegacy(loose), new Set(destinations.map((d) => d.id))),
  };
}

function sameLocation(a: LatLng, b: LatLng): boolean {
  return a.lat === b.lat && a.lng === b.lng;
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
  addDestination: (dest: DestinationDraft & Partial<Pick<Destination, 'status' | 'favorite' | 'visit'>>) => string;
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
  /** set the trip's date range (end is clamped to the maximum trip length) */
  setTripDates: (startDate: string, endDate: string) => void;
  /** put a destination on a day (before position `beforeIndex`, or last), or unschedule it with null */
  moveStop: (id: string, dayIndex: number | null, beforeIndex?: number) => void;
  /** spread the unscheduled destinations over the trip; returns how many were placed */
  autoPlan: () => number;
  /** replace the whole plan, e.g. to undo an auto-plan */
  setPlan: (plan: string[][]) => void;
  replaceAllData: (data: VacationMapData) => void;
  resetToBundledDefaults: () => Promise<void>;
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
            set({ data: migrateVacationMapData(json), isLoaded: true, loadError: null });
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
          const moved = !sameLocation(current.mainLocation.location, loc.location);
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
          const id = uuidv4();
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

        moveStop: (id, dayIndex, beforeIndex) =>
          updateTrip((trip) => ({ ...trip, plan: movePlanStop(trip.plan, id, dayIndex, beforeIndex) })),

        autoPlan: () => {
          const current = get().data;
          if (!current) return 0;
          const { plan, count } = autoPlanTrip(current.trip, current.mainLocation, current.destinations);
          if (count > 0) set({ data: { ...current, trip: { ...current.trip, plan } } });
          return count;
        },

        setPlan: (plan) => updateTrip((trip) => ({ ...trip, plan })),

        replaceAllData: (data) =>
          set({ data: migrateVacationMapData(data), selectedDestinationId: null, loadError: null }),

        resetToBundledDefaults: async () => {
          try {
            const res = await fetch(DATA_URL);
            if (!res.ok) throw new Error(`Failed to fetch seed data: HTTP ${res.status}`);
            const json = await res.json();
            if (!isValidVacationMapData(json)) throw new Error('Seed data has an invalid shape');
            set({ data: migrateVacationMapData(json), selectedDestinationId: null, loadError: null });
          } catch (err) {
            set({ loadError: err instanceof Error ? err.message : 'Failed to reset data' });
          }
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
