import { ApiError, GoogleGenAI } from '@google/genai';
import { v4 as uuidv4 } from 'uuid';
import type { Destination } from '../types/models';
import type { AiFinding, AiPhoto, DestinationAiResult } from '../types/ai';
import { looksGarbled } from './aiFindings';
import { fetchImagesForDestination, fetchSight, type SightMatch } from './wikimediaService';

const GEMINI_MODEL = 'gemini-3.5-flash-lite';
// Research asks this model first, with Google Search grounding, so the sights
// and their links come from pages it has just read (see researchThings).
const GROUNDED_MODEL = 'gemini-3.8-flash';

// Free-tier Gemini quota allows only a handful of requests per minute.
// Researching destinations one at a time with spacing, plus backing off on
// 429s, keeps us under that ceiling instead of bursting one request per
// destination and getting rate-limited.
const DELAY_BETWEEN_DESTINATIONS_MS = 4000;
const MAX_RETRIES_ON_RATE_LIMIT = 3;
const RETRY_BASE_DELAY_MS = 8000;

// Every prompt here asks for JSON. A low temperature keeps the small model
// from drifting: at its default it wrote corrupted Greek (Latin, Cyrillic,
// even Chinese letters inside words) in about one answer in five.
const GENERATION_CONFIG = { temperature: 0.2, responseMimeType: 'application/json' };

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

// The model names the sights and a link for each; their photos come from the
// Wikimedia APIs (see wikimediaService). Grounded, it searches the web first
// and takes the links from what it found; otherwise both come from its own
// knowledge.
function promptFor(dest: Destination, lang: string, grounded: boolean): string {
  const language = LANGUAGE_NAMES[lang] ?? 'English';
  const url = grounded
    ? 'the address of one page about it that your search found (official site, tourism board or Wikipedia), or "" if it found none'
    : 'one relevant, well-known, stable web page about it (official site, tourism board or Wikipedia), or "" if you are not confident one exists';
  return `You are researching the travel destination "${dest.name}" (near latitude ${dest.location.lat}, longitude ${dest.location.lng}).${grounded ? '\nUse Google Search to check what is worth seeing and doing there now.' : ''}
Suggest 6 specific sights or things to do there. For each give:
- "name": its short proper name (the sight, beach, museum, walk, market…), in ${language}
- "text": one or two sentences on what it is and why it is worth the visit, in ${language}
- "url": ${url}
- "wiki": the title of its English Wikipedia article, or "" if it has none
Return ONLY a JSON object of the shape:
{"things": [{"name": "", "text": "", "url": "", "wiki": ""}]}
No markdown formatting, no code fences, no extra commentary.`;
}

function stripCodeFences(raw: string): string {
  return raw.replace(/^\s*```(?:json)?\s*/i, '').replace(/\s*```\s*$/, '');
}

/** The JSON in an answer that may have a sentence or code fences around it. */
function parseJsonAnswer(raw: string): unknown {
  const text = stripCodeFences(raw);
  try {
    return JSON.parse(text);
  } catch {
    // a grounded answer can't be forced to be JSON only: take the object inside it
    const start = text.indexOf('{');
    const end = text.lastIndexOf('}');
    if (start < 0 || end <= start) throw new Error('No JSON in the answer.');
    return JSON.parse(text.slice(start, end + 1));
  }
}

// A grounded answer may cite Google's own redirect addresses, which stop
// working after a while: not something to save with a place.
const SEARCH_REDIRECT_URL = /^https?:\/\/vertexaisearch\.cloud\.google\.com\//i;

function isKeepableUrl(url: string): boolean {
  return /^https?:\/\//i.test(url) && !SEARCH_REDIRECT_URL.test(url);
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
    const parsed = parseJsonAnswer(rawText);
    // older prompt shapes: {"facts": [...]} or a bare array of sentences
    const obj = parsed as { things?: unknown; facts?: unknown } | null;
    const list = Array.isArray(parsed) ? parsed : (obj?.things ?? obj?.facts);
    if (Array.isArray(list)) {
      return list
        .map((item: unknown): RawThing => {
          if (typeof item === 'string') return sentence(item.trim());
          const o = (item && typeof item === 'object' ? item : {}) as Record<string, unknown>;
          const url = str(o.url);
          return { name: str(o.name), text: str(o.text), url: isKeepableUrl(url) ? url : '', wiki: str(o.wiki) };
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
        config: GENERATION_CONFIG,
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

// How long research goes without Google Search after a grounded call was
// refused for quota. A free-tier key has no search quota at all (every
// grounded call is a 429, whatever the model), so asking again for each
// place would only spend a request and the time it takes to fail.
const GROUNDING_PAUSE_MS = 10 * 60_000;
let groundingPausedUntil = 0;

/**
 * The sights for a place: from the grounded model when it answers, else from
 * the plain one. Only the plain call is retried and may throw; a grounded
 * call that fails for any reason just falls through to it.
 */
async function researchThings(dest: Destination, lang: string): Promise<{ things: RawThing[]; grounded: boolean }> {
  const apiKey = import.meta.env.VITE_GEMINI_API_KEY;
  if (!apiKey) throw new GeminiApiKeyMissingError();
  // a sight whose text came out corrupted is left out rather than shown
  const readable = (rawText: string | undefined) =>
    parseThings(rawText).filter((thing) => !looksGarbled(`${thing.name} ${thing.text}`, lang));

  if (Date.now() >= groundingPausedUntil) {
    try {
      const response = await new GoogleGenAI({ apiKey }).models.generateContent({
        model: GROUNDED_MODEL,
        contents: promptFor(dest, lang, true),
        // JSON mode is left off: the API has refused it together with tools, and
        // parseJsonAnswer copes with an answer that has more than the JSON in it
        config: { temperature: GENERATION_CONFIG.temperature, tools: [{ googleSearch: {} }] },
      });
      const things = readable(response.text).filter((thing) => thing.name);
      // prose instead of the JSON asked for reads as no named sights
      if (things.length > 0) return { things, grounded: true };
      console.warn(`[gemini] grounded research for "${dest.name}" gave nothing usable; asking without search.`);
    } catch (err) {
      console.warn(`[gemini] grounded research for "${dest.name}" failed; asking without search:`, err);
      if (isRateLimitError(err)) groundingPausedUntil = Date.now() + GROUNDING_PAUSE_MS;
    }
  }
  const rawText = await generateWithRetries(promptFor(dest, lang, false), `research for "${dest.name}"`);
  return { things: readable(rawText), grounded: false };
}

export async function fetchAiFindingsForDestination(dest: Destination, lang = 'en'): Promise<DestinationAiResult> {
  const base = {
    destinationId: dest.id,
    destinationName: dest.name,
  };

  try {
    // The photos around the destination load in parallel with the Gemini call.
    const [{ things, grounded }, areaPhotos] = await Promise.all([
      researchThings(dest, lang),
      fetchImagesForDestination(dest),
    ]);
    const findings = await toFindings(things, dest, areaPhotos);

    return { ...base, status: 'success', grounded, findings };
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
  lang = 'en',
): Promise<DestinationAiResult[]> {
  const results: DestinationAiResult[] = [];
  for (let i = 0; i < destinations.length; i++) {
    if (i > 0) await sleep(DELAY_BETWEEN_DESTINATIONS_MS);
    results.push(await fetchAiFindingsForDestination(destinations[i], lang));
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
    const blurb = typeof o.blurb === 'string' ? o.blurb.trim() : '';
    if (looksGarbled(`${o.name} ${blurb}`, req.lang)) return [];
    return [
      {
        name: o.name.trim(),
        lat,
        lng,
        blurb,
        tags: strings(o.tags),
        groups: strings(o.groups),
        budget: Number(o.budget) || 2,
        mustHaves: strings(o.mustHaves),
        ferry: o.ferry === true,
      },
    ];
  });
}
