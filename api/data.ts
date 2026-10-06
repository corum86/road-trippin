/// <reference types="node" />
import { MongoClient, type Collection, type Document } from 'mongodb';

/**
 * Cloud copy of the vacation data: one MongoDB document per map, keyed by the
 * map's sync code. Runs as a Vercel Function; the Vite dev and preview servers
 * mount the same handlers (see vite.config.ts).
 *
 *   GET /api/data?id=<code>[&rev=<n>]  → { revision, data? }
 *   PUT /api/data?id=<code>  { data }  → { revision }
 *
 * `revision` counts the saves and is null while nothing is saved under the
 * code. A GET that names the current revision gets no `data` back, so checking
 * for changes doesn't re-download the map.
 *
 * Kept free of imports from src/: Vercel compiles this file on its own.
 */

interface MapDocument {
  /** the sync code */
  _id: string;
  revision: number;
  updatedAt: Date;
  data: Document;
}

// the app's sync codes are v4 UUIDs: unguessable, so knowing one is the access check
const SYNC_CODE_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
// Vercel rejects larger request bodies itself; enforced here so local dev behaves the same
const MAX_BODY_BYTES = 4.5 * 1024 * 1024;

let clientPromise: Promise<MongoClient> | undefined;

/** The maps collection, on a connection reused across requests; null when no database is configured. */
async function getMaps(): Promise<Collection<MapDocument> | null> {
  const uri = process.env.MONGODB_URI;
  if (!uri) return null;
  if (!clientPromise) {
    const connecting = new MongoClient(uri, { serverSelectionTimeoutMS: 8000 }).connect();
    clientPromise = connecting;
    // a failed connection must not be cached: the next request tries again
    connecting.catch(() => {
      if (clientPromise === connecting) clientPromise = undefined;
    });
  }
  const client = await clientPromise;
  return client.db(process.env.MONGODB_DB || 'vacation_map').collection<MapDocument>('maps');
}

function json(body: unknown, status = 200): Response {
  return Response.json(body, { status, headers: { 'cache-control': 'no-store' } });
}

/** Same shape check the app runs on imported files. */
function isVacationMapData(value: unknown): value is Document {
  if (!value || typeof value !== 'object') return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v.version === 'number' &&
    // an object, or null on a map with no home base yet
    typeof v.mainLocation === 'object' &&
    Array.isArray(v.destinations)
  );
}

/** Resolve the request's map and run `action` on it, turning failures into JSON errors. */
async function withMap(
  request: Request,
  action: (maps: Collection<MapDocument>, id: string, url: URL) => Promise<Response>,
): Promise<Response> {
  const url = new URL(request.url);
  const id = url.searchParams.get('id') ?? '';
  if (!SYNC_CODE_PATTERN.test(id)) return json({ error: 'invalid_id' }, 400);
  try {
    const maps = await getMaps();
    // 501 rather than 503: the app stops asking instead of retrying
    if (!maps) return json({ error: 'not_configured' }, 501);
    return await action(maps, id, url);
  } catch (err) {
    console.error('vacation data request failed:', err);
    return json({ error: 'database_unavailable' }, 503);
  }
}

export function GET(request: Request): Promise<Response> {
  return withMap(request, async (maps, id, url) => {
    const known = url.searchParams.get('rev');
    if (known !== null) {
      const head = await maps.findOne({ _id: id }, { projection: { revision: 1 } });
      if (!head) return json({ revision: null });
      if (String(head.revision) === known) return json({ revision: head.revision });
    }
    const doc = await maps.findOne({ _id: id });
    return json(doc ? { revision: doc.revision, data: doc.data } : { revision: null });
  });
}

export function PUT(request: Request): Promise<Response> {
  return withMap(request, async (maps, id) => {
    if (Number(request.headers.get('content-length')) > MAX_BODY_BYTES) {
      return json({ error: 'too_large' }, 413);
    }
    const body: unknown = await request.json().catch(() => null);
    const data = body && typeof body === 'object' ? (body as { data?: unknown }).data : undefined;
    if (!isVacationMapData(data)) return json({ error: 'invalid_data' }, 400);
    const saved = await maps.findOneAndUpdate(
      { _id: id },
      { $set: { data, updatedAt: new Date() }, $inc: { revision: 1 } },
      { upsert: true, returnDocument: 'after', projection: { revision: 1 } },
    );
    if (!saved) throw new Error('upsert returned no document');
    return json({ revision: saved.revision });
  });
}
