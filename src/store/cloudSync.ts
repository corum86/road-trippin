import { create } from 'zustand';
import { v4 as uuidv4 } from 'uuid';
import { CURRENT_DATA_VERSION, type VacationMapData } from '../types/models';
import {
  CloudRequestError,
  fetchCloudData,
  normalizeSyncCode,
  saveCloudData,
} from '../services/cloudDataService';
import { migrateVacationMapData, useMapDataStore } from './mapDataStore';

/**
 * Keeps the map saved in MongoDB (through /api/data) in step with the store.
 *
 * localStorage stays the working copy, so the app opens at once and works
 * offline. This module uploads every change and brings in what other devices
 * with the same sync code saved. It syncs the map as a whole and the last
 * write wins: a device with changes of its own uploads them over the cloud copy.
 */

const META_KEY = 'vacation-map-sync';
// long enough to gather a burst of edits (dragging stops, typing) into one save
const SAVE_DELAY_MS = 1000;
const RETRY_DELAY_MS = 30_000;

export type SyncStatus =
  | 'connecting'
  /** no backend to talk to: the data lives on this device only */
  | 'unavailable'
  | 'saving'
  | 'synced'
  /** the cloud can't be reached; changes wait on this device */
  | 'offline'
  /** the map is over the backend's size limit */
  | 'tooLarge'
  /** the cloud copy was written by a newer build than this one */
  | 'outdated';

export type ConnectResult = 'ok' | 'invalid' | 'notFound' | 'outdated' | 'failed';

interface CloudSyncState {
  status: SyncStatus;
  /** names this device's cloud copy; devices that use the same code share one map */
  syncCode: string;
  /** switch this device to the map saved under `code`, replacing its data */
  connect: (code: string) => Promise<ConnectResult>;
}

interface SyncMeta {
  syncCode: string;
  /** the cloud revision this device last saw; null while nothing is saved under the code */
  revision: number | null;
  /** this device has changes the cloud copy lacks */
  dirty: boolean;
}

function readMeta(): SyncMeta | null {
  try {
    const saved = JSON.parse(localStorage.getItem(META_KEY) ?? 'null') as Partial<SyncMeta> | null;
    const syncCode = typeof saved?.syncCode === 'string' ? normalizeSyncCode(saved.syncCode) : null;
    if (!saved || !syncCode) return null;
    return {
      syncCode,
      revision: typeof saved.revision === 'number' ? saved.revision : null,
      dirty: saved.dirty !== false,
    };
  } catch {
    return null;
  }
}

// On the first run, data already in this browser predates cloud sync and is
// uploaded; a fresh browser only holds the bundled seed, which isn't.
let meta: SyncMeta = readMeta() ?? {
  syncCode: uuidv4(),
  revision: null,
  dirty: useMapDataStore.getState().data !== null,
};

function saveMeta(patch: Partial<SyncMeta>) {
  meta = { ...meta, ...patch };
  try {
    localStorage.setItem(META_KEY, JSON.stringify(meta));
  } catch {
    // storage unavailable or full: syncing still works for this session
  }
}

/** the store's data object known to match the cloud copy */
let syncedData: VacationMapData | null = null;
let saveTimer: number | undefined;
let retryTimer: number | undefined;
let running = false;
let started = false;

/** Replace this device's data with the cloud copy. */
function adopt(remote: VacationMapData, revision: number) {
  const data = migrateVacationMapData(remote);
  syncedData = data;
  saveMeta({ revision, dirty: false });
  const { selectedDestinationId } = useMapDataStore.getState();
  useMapDataStore.setState({
    data,
    loadError: null,
    // an open place stays open if the cloud copy still has it
    selectedDestinationId: data.destinations.some((d) => d.id === selectedDestinationId)
      ? selectedDestinationId
      : null,
  });
}

export const useCloudSyncStore = create<CloudSyncState>()((set) => ({
  status: 'connecting',
  syncCode: meta.syncCode,

  connect: async (code) => {
    const syncCode = normalizeSyncCode(code);
    if (!syncCode) return 'invalid';
    if (syncCode === meta.syncCode) return 'ok';
    try {
      const snapshot = await fetchCloudData(syncCode, null);
      if (snapshot.revision === null || !snapshot.data) return 'notFound';
      if (snapshot.data.version > CURRENT_DATA_VERSION) return 'outdated';
      window.clearTimeout(saveTimer);
      window.clearTimeout(retryTimer);
      // changes not yet saved under the old code are dropped along with its data
      saveMeta({ syncCode });
      adopt(snapshot.data, snapshot.revision);
      set({ syncCode, status: 'synced' });
      return 'ok';
    } catch {
      return 'failed';
    }
  },
}));

function setStatus(status: SyncStatus) {
  useCloudSyncStore.setState({ status });
}

/** Syncing can't work for the rest of this session. */
function isStopped(): boolean {
  const { status } = useCloudSyncStore.getState();
  return status === 'unavailable' || status === 'outdated';
}

/** Bring in what another device saved. */
async function pull() {
  const { syncCode } = meta;
  const snapshot = await fetchCloudData(syncCode, meta.revision);
  // switched to another map, or edited, while the request was out
  if (meta.syncCode !== syncCode || meta.dirty) return;
  if (snapshot.revision === null) {
    // the cloud copy this device once saved is gone: save it again
    if (meta.revision !== null) saveMeta({ revision: null, dirty: true });
    return;
  }
  if (!snapshot.data) return;
  // a newer build's data would lose fields on its way through this one
  if (snapshot.data.version > CURRENT_DATA_VERSION) setStatus('outdated');
  else adopt(snapshot.data, snapshot.revision);
}

/** Upload the current data; changes made meanwhile leave it dirty for another round. */
async function push() {
  const { syncCode } = meta;
  const data = useMapDataStore.getState().data;
  if (!data) {
    saveMeta({ dirty: false });
    return;
  }
  setStatus('saving');
  const revision = await saveCloudData(syncCode, data);
  if (meta.syncCode !== syncCode) return;
  syncedData = data;
  saveMeta({ revision, dirty: useMapDataStore.getState().data !== data });
}

/** One round with the cloud: upload this device's changes, or else fetch the others'. */
async function sync() {
  window.clearTimeout(saveTimer);
  // a round already under way picks up changes made since it started
  if (running || isStopped()) return;
  running = true;
  window.clearTimeout(retryTimer);
  try {
    if (!meta.dirty) await pull();
    while (meta.dirty && !isStopped()) await push();
    if (!isStopped()) setStatus('synced');
  } catch (err) {
    if (err instanceof CloudRequestError && err.noBackend) {
      setStatus('unavailable');
    } else if (err instanceof CloudRequestError && err.tooLarge) {
      // sending it again unchanged would fail the same way; the next edit retries
      setStatus('tooLarge');
    } else {
      setStatus('offline');
      retryTimer = window.setTimeout(() => void sync(), RETRY_DELAY_MS);
    }
  } finally {
    running = false;
  }
}

/** Start syncing the store with the cloud. Call once, at startup. */
export function startCloudSync() {
  if (started) return;
  started = true;
  saveMeta({});

  useMapDataStore.subscribe((state, prev) => {
    if (state.data === prev.data || state.data === syncedData) return;
    // the first data a fresh browser gets is the bundled seed: nothing of the user's to save
    if (!prev.data) return;
    if (!meta.dirty) saveMeta({ dirty: true });
    if (isStopped()) return;
    if (useCloudSyncStore.getState().status === 'synced') setStatus('saving');
    window.clearTimeout(saveTimer);
    saveTimer = window.setTimeout(() => void sync(), SAVE_DELAY_MS);
  });

  window.addEventListener('online', () => void sync());
  window.addEventListener('focus', () => void sync());
  document.addEventListener('visibilitychange', () => {
    // coming back: look for changes from elsewhere; leaving: don't sit on a pending save
    if (document.visibilityState === 'visible' || meta.dirty) void sync();
  });

  void sync();
}
