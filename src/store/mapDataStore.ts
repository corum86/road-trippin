import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { v4 as uuidv4 } from 'uuid';
import {
  CURRENT_DATA_VERSION,
  type Destination,
  type DestinationDraft,
  type ItineraryDay,
  type LatLng,
  type MainLocation,
  type Photo,
  type Rating,
  type RouteInfo,
  type TripStatus,
  type VacationMapData,
  type VisitLog,
} from '../types/models';
import { todayIso } from '../services/dates';

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

/**
 * Bring data of any earlier version up to the current shape. Idempotent, so
 * it is safe to run on every entry point: seed fetch, localStorage
 * rehydration, file import and reset.
 */
export function migrateVacationMapData(raw: VacationMapData): VacationMapData {
  // v1 data lacks these fields entirely; read through a loose view of it
  const loose = raw as Partial<VacationMapData>;
  return {
    ...raw,
    version: CURRENT_DATA_VERSION,
    itinerary: Array.isArray(loose.itinerary) ? loose.itinerary : [],
    destinations: raw.destinations.map((d) => {
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
    }),
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
  setItinerary: (days: ItineraryDay[]) => void;
  replaceAllData: (data: VacationMapData) => void;
  resetToBundledDefaults: () => Promise<void>;
}

export const useMapDataStore = create<MapDataState>()(
  persist(
    (set, get) => {
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
              itinerary: current.itinerary.map((day) => ({
                ...day,
                stopIds: day.stopIds.filter((stopId) => stopId !== id),
              })),
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

        setItinerary: (days) => {
          const current = get().data;
          if (!current) return;
          set({ data: { ...current, itinerary: days } });
        },

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
