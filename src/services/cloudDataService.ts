import type { VacationMapData } from '../types/models';

const DATA_API_URL = '/api/data';

// sync codes are v4 UUIDs: unguessable, so knowing one is what grants access to a map
const SYNC_CODE_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

/** A code as typed or pasted, in the form the backend expects; null if it can't be one. */
export function normalizeSyncCode(code: string): string | null {
  const normalized = code.trim().toLowerCase();
  return SYNC_CODE_PATTERN.test(normalized) ? normalized : null;
}

/** The backend answered, but not with what was asked for. */
export class CloudRequestError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`Cloud data request failed: HTTP ${status}`);
    this.status = status;
  }

  /** nothing is listening (static hosting) or no database is configured: not worth retrying */
  get noBackend(): boolean {
    return this.status === 404 || this.status === 405 || this.status === 501;
  }

  get tooLarge(): boolean {
    return this.status === 413;
  }
}

export interface CloudSnapshot {
  /** counts the saves; null while nothing is saved under the code */
  revision: number | null;
  /** absent when the revision asked about is still the current one */
  data?: VacationMapData;
}

async function request<T>(syncCode: string, query: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${DATA_API_URL}?id=${syncCode}${query}`, { ...init, cache: 'no-store' });
  if (!res.ok) throw new CloudRequestError(res.status);
  // a static host may answer any path with its HTML page
  if (!res.headers.get('content-type')?.includes('application/json')) throw new CloudRequestError(404);
  return (await res.json()) as T;
}

/** The map saved under `syncCode`; without its data if `knownRevision` is still current. */
export function fetchCloudData(syncCode: string, knownRevision: number | null): Promise<CloudSnapshot> {
  return request<CloudSnapshot>(syncCode, knownRevision === null ? '' : `&rev=${knownRevision}`);
}

/** Save `data` under `syncCode`, replacing what is there; resolves to the new revision. */
export async function saveCloudData(syncCode: string, data: VacationMapData): Promise<number> {
  const saved = await request<{ revision: number }>(syncCode, '', {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ data }),
  });
  return saved.revision;
}
