import { ApiError, GoogleGenAI } from '@google/genai';
import { v4 as uuidv4 } from 'uuid';
import type { Destination } from '../types/models';
import type { AiFinding, AiPhoto, DestinationAiResult } from '../types/ai';
import { fetchImagesForDestination, fetchSight, type SightMatch } from './wikimediaService';

const GEMINI_MODEL = 'gemini-3.5-flash-lite';

// Free-tier Gemini quota allows only a handful of requests per minute.
// Researching destinations one at a time with spacing, plus backing off on
// 429s, keeps us under that ceiling instead of bursting one request per
// destination and getting rate-limited.
const DELAY_BETWEEN_DESTINATIONS_MS = 4000;
const MAX_RETRIES_ON_RATE_LIMIT = 3;
const RETRY_BASE_DELAY_MS = 8000;

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function isRateLimitError(err: unknown): boolean {
  return err instanceof ApiError && err.status === 429;
}

// 503 = model temporarily overloaded on Google's side. Retry once right
// away; if it happens again, wait this long and try one final time.
const MAX_RETRIES_ON_UNAVAILABLE = 2;
const UNAVAILABLE_RETRY_DELAY_MS = 30_000;

function isUnavailableError(err: unknown): boolean {
  return err instanceof ApiError && err.status === 503;
}

export class GeminiApiKeyMissingError extends Error {
  constructor() {
    super('No Gemini API key configured (VITE_GEMINI_API_KEY).');
    this.name = 'GeminiApiKeyMissingError';
  }
}

// Minimal, single-shot call used to isolate where a 429/error comes from.
// withSearch=false: plain call, no tools — fails only on general API quota.
// withSearch=true: same call plus the googleSearch tool — if this fails but
// the plain call doesn't, the search-grounding tool has its own, separate,
// much stricter quota (a common free-tier gotcha independent of the model's
// base quota, and not something AI Studio's chat playground exercises the
// same way, which is why it can work there but not here).
export async function testGeminiConnection(
  withSearch: boolean,
): Promise<{ ok: true; text: string } | { ok: false; error: string }> {
  const apiKey = import.meta.env.VITE_GEMINI_API_KEY;
  if (!apiKey) throw new GeminiApiKeyMissingError();

  try {
    const ai = new GoogleGenAI({ apiKey });
    const response = await ai.models.generateContent({
      model: GEMINI_MODEL,
      contents: withSearch
        ? 'Use Google Search to find today\'s top headline, then reply with exactly one sentence.'
        : 'Reply with exactly one word: pong',
      ...(withSearch ? { config: { tools: [{ googleSearch: {} }] } } : {}),
    });
    return { ok: true, text: response.text ?? '(empty response)' };
  } catch (err) {
    console.error(`[gemini] test call (withSearch=${withSearch}) failed:`, err);
    return {
      ok: false,
      error:
        err instanceof ApiError
          ? `HTTP ${err.status}: ${err.message}`
          : err instanceof Error
            ? err.message
            : 'Unknown error.',
    };
  }
}

const LANGUAGE_NAMES: Record<string, string> = {
  en: 'English',
  el: 'Greek',
};

// Google Search grounding has its own free-tier quota that 429s even when
// plain generateContent works fine (verified across several models). So the
// model names the sights and a well-known link from its own knowledge, and
// their photos come from the Wikimedia APIs instead (see wikimediaService).
function promptFor(dest: Destination, lang: string): string {
  const language = LANGUAGE_NAMES[lang] ?? 'English';
  return `You are researching the travel destination "${dest.name}" (near latitude ${dest.location.lat}, longitude ${dest.location.lng}).
Suggest 6 specific sights or things to do there. For each give:
- "name": its short proper name (the sight, beach, museum, walk, market…), in ${language}
- "text": one or two sentences on what it is and why it is worth the visit, in ${language}
- "url": one relevant, well-known, stable web page about it (official site, tourism board or Wikipedia), or "" if you are not confident one exists
- "wiki": the title of its English Wikipedia article, or "" if it has none
Return ONLY a JSON object of the shape:
{"things": [{"name": "", "text": "", "url": "", "wiki": ""}]}
No markdown formatting, no code fences, no extra commentary.`;
}

function stripCodeFences(raw: string): string {
  return raw.replace(/^\s*```(?:json)?\s*/i, '').replace(/\s*```\s*$/, '');
}

/** A sight as the model describes it, before its photo is looked up. */
interface RawThing {
  name: string;
  text: string;
  url: string;
  wiki: string;
}

function parseThings(rawText: string | undefined): RawThing[] {
  if (!rawText) return [];
  const sentence = (text: string): RawThing => ({ name: '', text, url: '', wiki: '' });
  const str = (v: unknown) => (typeof v === 'string' ? v.trim() : '');
  try {
    const parsed: unknown = JSON.parse(stripCodeFences(rawText));
    // older prompt shapes: {"facts": [...]} or a bare array of sentences
    const obj = parsed as { things?: unknown; facts?: unknown } | null;
    const list = Array.isArray(parsed) ? parsed : (obj?.things ?? obj?.facts);
    if (Array.isArray(list)) {
      return list
        .map((item: unknown): RawThing => {
          if (typeof item === 'string') return sentence(item.trim());
          const o = (item && typeof item === 'object' ? item : {}) as Record<string, unknown>;
          const url = str(o.url);
          return { name: str(o.name), text: str(o.text), url: /^https?:\/\//i.test(url) ? url : '', wiki: str(o.wiki) };
        })
        .filter((thing) => thing.name || thing.text);
    }
  } catch {
    // fall through to the line-splitting fallback below
  }
  return rawText
    .split('\n')
    .map((line) => line.replace(/^[-*\d.)\s]+/, '').trim())
    .filter((line) => line.length > 0)
    .map(sentence);
}

const WIKIPEDIA_URL = /^https?:\/\/[^/]*\bwikipedia\.org\//i;

/** "Bourtzi Castle" from https://en.wikipedia.org/wiki/Bourtzi_Castle */
function englishWikipediaTitle(url: string): string | undefined {
  const path = /^https?:\/\/en\.(?:m\.)?wikipedia\.org\/wiki\/([^?#]+)/i.exec(url)?.[1];
  if (!path) return undefined;
  try {
    return decodeURIComponent(path).replace(/_/g, ' ');
  } catch {
    return undefined;
  }
}

/**
 * Give each sight its photo and link: its own Wikipedia article or Commons
 * photo when one matches, else one of the photos taken around the
 * destination, so a card is only left without a picture when there are none.
 */
async function toFindings(things: RawThing[], dest: Destination, areaPhotos: AiPhoto[]): Promise<AiFinding[]> {
  const matches = await Promise.all(
    things.map((thing) =>
      thing.name
        ? fetchSight({
            name: thing.name,
            wikiTitle: thing.wiki || englishWikipediaTitle(thing.url),
            near: dest.location,
          })
        : Promise.resolve<SightMatch>({}),
    ),
  );
  const used = new Set<string>();
  const spare = [...areaPhotos];
  return things.map((thing, i) => {
    const { photo: own, article } = matches[i];
    // two sights can resolve to the same article: only the first keeps its picture
    let photo = own && !used.has(own.imageUrl) ? own : undefined;
    while (!photo && spare.length > 0) {
      const next = spare.shift();
      if (next && !used.has(next.imageUrl)) photo = next;
    }
    if (photo) used.add(photo.imageUrl);
    // a Wikipedia link comes from the lookup, which knows the article exists
    const ownUrl = WIKIPEDIA_URL.test(thing.url) ? '' : thing.url;
    const link = ownUrl
      ? { label: thing.name || ownUrl, url: ownUrl }
      : article
        ? { label: article.title, url: article.url }
        : undefined;
    return { id: uuidv4(), name: thing.name, text: thing.text, photo, link, added: false };
  });
}

// Runs a plain generateContent call with the shared retry policy:
// 429s back off exponentially, 503s (model overloaded) retry once
// immediately and once more after a 30s wait. Throws the final error
// once both budgets are exhausted.
async function generateWithRetries(contents: string, logContext: string): Promise<string | undefined> {
  const apiKey = import.meta.env.VITE_GEMINI_API_KEY;
  if (!apiKey) throw new GeminiApiKeyMissingError();

  const ai = new GoogleGenAI({ apiKey });
  let rateLimitRetries = 0;
  let unavailableRetries = 0;

  while (true) {
    try {
      const response = await ai.models.generateContent({
        model: GEMINI_MODEL,
        contents,
      });
      return response.text;
    } catch (err) {
      // Log the raw error so the actual Google error body (which usually
      // names the specific exhausted quota) is visible in devtools, not
      // just the possibly-truncated summary shown in the UI.
      console.error(`[gemini] ${logContext} failed:`, err);

      if (isRateLimitError(err) && rateLimitRetries < MAX_RETRIES_ON_RATE_LIMIT) {
        await sleep(RETRY_BASE_DELAY_MS * 2 ** rateLimitRetries);
        rateLimitRetries++;
        continue;
      }
      if (isUnavailableError(err) && unavailableRetries < MAX_RETRIES_ON_UNAVAILABLE) {
        // First 503: retry immediately. Second 503: wait 30s, then one
        // last try.
        if (unavailableRetries === 1) await sleep(UNAVAILABLE_RETRY_DELAY_MS);
        unavailableRetries++;
        continue;
      }
      throw err;
    }
  }
}

function formatGeminiError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (isRateLimitError(err)) {
      return `Gemini rate limit (429) exceeded — your free-tier quota has been used up for now. Try again later, or wait a minute between searches. (${err.message})`;
    }
    if (isUnavailableError(err)) {
      return `Gemini is temporarily overloaded (503) — retried ${MAX_RETRIES_ON_UNAVAILABLE + 1} times without success. Try again in a few minutes. (${err.message})`;
    }
    return `Gemini HTTP ${err.status}: ${err.message}`;
  }
  return err instanceof Error ? err.message : fallback;
}

export async function fetchAiFindingsForDestination(dest: Destination, lang = 'en'): Promise<DestinationAiResult> {
  const base = {
    destinationId: dest.id,
    destinationName: dest.name,
  };

  try {
    // Deliberately NO googleSearch tool here — see comment on promptFor.
    // The photos around the destination load in parallel with the Gemini call.
    const [rawText, areaPhotos] = await Promise.all([
      generateWithRetries(promptFor(dest, lang), `research for "${dest.name}"`),
      fetchImagesForDestination(dest),
    ]);
    const findings = await toFindings(parseThings(rawText), dest, areaPhotos);

    return { ...base, status: 'success', findings };
  } catch (err) {
    if (err instanceof GeminiApiKeyMissingError) throw err;
    return {
      ...base,
      status: 'error',
      error: formatGeminiError(err, 'Gemini search failed.'),
      findings: [],
    };
  }
}

export interface DestinationTranslationPatch {
  name: string;
  attractions: string[];
  notes?: string;
  links: Destination['links'];
}

// Translates a destination's saved textual content (name, attractions,
// notes, link labels) into the given language. URLs and ids are never
// touched. Returns a patch ready for updateDestination, or throws with a
// user-presentable message.
export async function translateDestinationContent(
  dest: Destination,
  targetLang: string,
): Promise<DestinationTranslationPatch> {
  const language = LANGUAGE_NAMES[targetLang] ?? targetLang;

  const payload = {
    name: dest.name,
    attractions: dest.attractions,
    notes: dest.notes ?? '',
    linkLabels: dest.links.map((l) => l.label),
  };

  const prompt = `Translate the string values in the JSON object below into ${language}.
Rules:
- Keep the exact same JSON shape and array lengths and order.
- Translate naturally; keep proper nouns in their conventional ${language} form (or unchanged if none exists).
- If a string is already in ${language}, return it unchanged.
- Return ONLY the JSON object, no markdown, no code fences, no commentary.

${JSON.stringify(payload)}`;

  const rawText = await (async () => {
    try {
      return await generateWithRetries(prompt, `translation of "${dest.name}" to ${language}`);
    } catch (err) {
      if (err instanceof GeminiApiKeyMissingError) throw err;
      throw new Error(formatGeminiError(err, 'Translation failed.'));
    }
  })();

  let parsed: unknown;
  try {
    parsed = JSON.parse(stripCodeFences(rawText ?? ''));
  } catch {
    throw new Error('Translation failed: Gemini returned an unexpected format.');
  }
  const obj = parsed as Partial<typeof payload> | null;
  if (!obj || typeof obj !== 'object') {
    throw new Error('Translation failed: Gemini returned an unexpected format.');
  }

  // Only accept fields that came back with the right shape; anything
  // malformed falls back to the original value rather than corrupting data.
  const attractions =
    Array.isArray(obj.attractions) &&
    obj.attractions.length === dest.attractions.length &&
    obj.attractions.every((a): a is string => typeof a === 'string')
      ? obj.attractions
      : dest.attractions;
  const linkLabels =
    Array.isArray(obj.linkLabels) &&
    obj.linkLabels.length === dest.links.length &&
    obj.linkLabels.every((l): l is string => typeof l === 'string')
      ? obj.linkLabels
      : dest.links.map((l) => l.label);

  return {
    name: typeof obj.name === 'string' && obj.name.trim() ? obj.name.trim() : dest.name,
    attractions,
    notes: typeof obj.notes === 'string' && obj.notes.trim() ? obj.notes : dest.notes,
    links: dest.links.map((link, i) => ({ ...link, label: linkLabels[i] || link.label })),
  };
}

export async function fetchAiFindingsForAllDestinations(
  destinations: Destination[],
  onProgress?: (completed: number, total: number) => void,
): Promise<DestinationAiResult[]> {
  const results: DestinationAiResult[] = [];
  for (let i = 0; i < destinations.length; i++) {
    if (i > 0) await sleep(DELAY_BETWEEN_DESTINATIONS_MS);
    results.push(await fetchAiFindingsForDestination(destinations[i]));
    onProgress?.(i + 1, destinations.length);
  }
  return results;
}

/** A candidate place as the model describes it, before routing and validation. */
export interface RawPlaceSuggestion {
  name: string;
  lat: number;
  lng: number;
  blurb: string;
  tags: string[];
  groups: string[];
  budget: number;
  mustHaves: string[];
  ferry: boolean;
}

export interface SuggestionRequest {
  homeName: string;
  home: { lat: number; lng: number };
  startDate?: string;
  endDate?: string;
  lang: string;
}

// Asked once per planner run, before the traveller's answers are in: the
// list is broad, and the app ranks it by their answers on the device.
function suggestionsPrompt(req: SuggestionRequest): string {
  const when = req.startDate ? ` The trip runs ${req.startDate} to ${req.endDate ?? req.startDate}.` : '';
  return `You are a local travel expert. A traveller stays at "${req.homeName}" (latitude ${req.home.lat}, longitude ${req.home.lng}) and makes day trips from there by car.${when}
Suggest 18 varied day-trip destinations within about 2.5 hours one-way drive, nearest first, including islands reachable by car ferry if there are any.
For each give: a short name, accurate latitude and longitude, a one-sentence blurb, and these attributes:
- "tags": any of ["relaxed","active","sightseeing","culture","food","nature"]
- "groups": who it suits, any of ["couple","family","friends"]
- "budget": 1 (cheap) to 3 (expensive)
- "mustHaves": any of ["beach","food","kids","nightlife"]
- "ferry": true only if reaching it requires a ferry crossing
Write names and blurbs in ${LANGUAGE_NAMES[req.lang] ?? 'English'}. Only include real places you are confident exist at those coordinates.
Return ONLY a JSON array of objects: [{"name":"","lat":0,"lng":0,"blurb":"","tags":[],"groups":[],"budget":1,"mustHaves":[],"ferry":false}]
No markdown formatting, no code fences, no extra commentary.`;
}

/** Ask Gemini for day-trip ideas around the home base (throws a user-presentable message). */
export async function fetchPlaceSuggestions(req: SuggestionRequest): Promise<RawPlaceSuggestion[]> {
  let rawText: string | undefined;
  try {
    rawText = await generateWithRetries(suggestionsPrompt(req), `place suggestions around "${req.homeName}"`);
  } catch (err) {
    if (err instanceof GeminiApiKeyMissingError) throw err;
    throw new Error(formatGeminiError(err, 'Suggestions failed.'));
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(stripCodeFences(rawText ?? ''));
  } catch {
    throw new Error('Gemini returned an unexpected format.');
  }
  if (!Array.isArray(parsed)) throw new Error('Gemini returned an unexpected format.');
  const strings = (v: unknown) => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []);
  return parsed.flatMap((item): RawPlaceSuggestion[] => {
    if (!item || typeof item !== 'object') return [];
    const o = item as Record<string, unknown>;
    const lat = Number(o.lat);
    const lng = Number(o.lng);
    if (typeof o.name !== 'string' || !o.name.trim() || !Number.isFinite(lat) || !Number.isFinite(lng)) return [];
    return [
      {
        name: o.name.trim(),
        lat,
        lng,
        blurb: typeof o.blurb === 'string' ? o.blurb.trim() : '',
        tags: strings(o.tags),
        groups: strings(o.groups),
        budget: Number(o.budget) || 2,
        mustHaves: strings(o.mustHaves),
        ferry: o.ferry === true,
      },
    ];
  });
}
