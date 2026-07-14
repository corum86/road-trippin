import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import { v4 as uuidv4 } from 'uuid';
import type { Destination, MainLocation, RouteInfo, VacationMapData } from '../types/models';

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

interface MapDataState {
  data: VacationMapData | null;
  selectedDestinationId: string | null;
  isLoaded: boolean;
  loadError: string | null;
  _loadStarted: boolean;

  loadInitialData: () => Promise<void>;
  setMainLocation: (loc: MainLocation) => void;
  addDestination: (dest: Omit<Destination, 'id'>) => string;
  updateDestination: (id: string, patch: Partial<Omit<Destination, 'id'>>) => void;
  removeDestination: (id: string) => void;
  setSelectedDestination: (id: string | null) => void;
  setRouteInfo: (destinationId: string, info: RouteInfo) => void;
  replaceAllData: (data: VacationMapData) => void;
  resetToBundledDefaults: () => Promise<void>;
}

export const useMapDataStore = create<MapDataState>()(
  persist(
    (set, get) => ({
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
          set({ data: json, isLoaded: true, loadError: null });
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
        set({ data: { ...current, mainLocation: loc } });
      },

      addDestination: (dest) => {
        const current = get().data;
        const id = uuidv4();
        if (!current) return id;
        set({
          data: { ...current, destinations: [...current.destinations, { ...dest, id }] },
        });
        return id;
      },

      updateDestination: (id, patch) => {
        const current = get().data;
        if (!current) return;
        set({
          data: {
            ...current,
            destinations: current.destinations.map((d) =>
              d.id === id ? { ...d, ...patch } : d,
            ),
          },
        });
      },

      removeDestination: (id) => {
        const current = get().data;
        if (!current) return;
        set({
          data: {
            ...current,
            destinations: current.destinations.filter((d) => d.id !== id),
          },
          selectedDestinationId: get().selectedDestinationId === id ? null : get().selectedDestinationId,
        });
      },

      setSelectedDestination: (id) => set({ selectedDestinationId: id }),

      setRouteInfo: (destinationId, info) => {
        const current = get().data;
        if (!current) return;
        set({
          data: {
            ...current,
            destinations: current.destinations.map((d) =>
              d.id === destinationId ? { ...d, routeInfo: info } : d,
            ),
          },
        });
      },

      replaceAllData: (data) => set({ data, selectedDestinationId: null, loadError: null }),

      resetToBundledDefaults: async () => {
        try {
          const res = await fetch(DATA_URL);
          if (!res.ok) throw new Error(`Failed to fetch seed data: HTTP ${res.status}`);
          const json = await res.json();
          if (!isValidVacationMapData(json)) throw new Error('Seed data has an invalid shape');
          set({ data: json, selectedDestinationId: null, loadError: null });
        } catch (err) {
          set({ loadError: err instanceof Error ? err.message : 'Failed to reset data' });
        }
      },
    }),
    {
      name: 'vacation-map-data',
      partialize: (state) => ({ data: state.data }),
    },
  ),
);
