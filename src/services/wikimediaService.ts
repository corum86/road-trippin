import type { Destination, LatLng } from '../types/models';
import type { AiPhoto } from '../types/ai';

// Image search backed by the Wikimedia APIs instead of Gemini's Google
// Search grounding (not available on the free tier) or a paid CORS proxy.
// Both endpoints are free, need no API key, and send CORS headers when
// called with origin=*.
//
// Strategy:
//   1. Commons geosearch — photos actually taken near the destination's
//      coordinates (namespace 6 = File pages).
//   2. Fallback: Wikipedia article search by destination name, taking each
//      article's lead image.
//
// A single sight is looked up by name instead (see fetchSight): its
// Wikipedia article, else a Commons photo of that name taken nearby.

const MAX_IMAGES = 8;
const THUMB_WIDTH = 640;
const FETCH_TIMEOUT_MS = 8000;

// Skip non-photo files (maps, icons, audio) that geosearch can return.
const PHOTO_EXTENSIONS = /\.(jpe?g|png|webp)$/i;

interface CommonsImageInfo {
  thumburl?: string;
  url?: string;
  descriptionurl?: string;
}

interface CommonsPage {
  title?: string;
  /** rank in a search result (the API returns pages unordered) */
  index?: number;
  imageinfo?: CommonsImageInfo[];
}

interface WikipediaPage {
  title?: string;
  index?: number;
  /** present (as "") when no article has that title */
  missing?: string;
  fullurl?: string;
  thumbnail?: { source?: string };
}

function byRank<T extends { index?: number }>(pages: Record<string, T> | undefined): T[] {
  return Object.values(pages ?? {}).sort((a, b) => (a.index ?? 0) - (b.index ?? 0));
}

async function fetchJson(url: string): Promise<unknown> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);
  try {
    const res = await fetch(url, { signal: controller.signal });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  } finally {
    clearTimeout(timeout);
  }
}

function cleanFileTitle(title: string | undefined): string {
  return (title ?? '').replace(/^File:/, '').replace(PHOTO_EXTENSIONS, '');
}

function commonsPhotos(pages: CommonsPage[]): AiPhoto[] {
  return pages
    .filter((p) => PHOTO_EXTENSIONS.test(p.title ?? ''))
    .map((p): AiPhoto | null => {
      const info = p.imageinfo?.[0];
      const imageUrl = info?.thumburl || info?.url;
      if (!imageUrl) return null;
      return { imageUrl, sourceUrl: info?.descriptionurl || imageUrl, sourceTitle: cleanFileTitle(p.title) };
    })
    .filter((f): f is AiPhoto => f !== null);
}

async function searchCommonsNearby(dest: Destination): Promise<AiPhoto[]> {
  const params = new URLSearchParams({
    action: 'query',
    format: 'json',
    origin: '*',
    generator: 'geosearch',
    ggscoord: `${dest.location.lat}|${dest.location.lng}`,
    ggsradius: '10000', // meters (API max)
    ggslimit: String(MAX_IMAGES * 2),
    ggsnamespace: '6', // File pages, i.e. the photos themselves
    prop: 'imageinfo',
    iiprop: 'url',
    iiurlwidth: String(THUMB_WIDTH),
  });
  const data = (await fetchJson(`https://commons.wikimedia.org/w/api.php?${params}`)) as {
    query?: { pages?: Record<string, CommonsPage> };
  } | null;

  return commonsPhotos(Object.values(data?.query?.pages ?? {})).slice(0, MAX_IMAGES);
}

/** Wikipedia articles matching the text, best match first, each with its lead image. */
async function searchWikipedia(query: string, limit: number): Promise<AiPhoto[]> {
  const params = new URLSearchParams({
    action: 'query',
    format: 'json',
    origin: '*',
    generator: 'search',
    gsrsearch: query,
    gsrlimit: String(limit),
    prop: 'pageimages|info',
    piprop: 'thumbnail',
    pithumbsize: String(THUMB_WIDTH),
    inprop: 'url',
  });
  const data = (await fetchJson(`https://en.wikipedia.org/w/api.php?${params}`)) as {
    query?: { pages?: Record<string, WikipediaPage> };
  } | null;

  return byRank(data?.query?.pages)
    .map((p): AiPhoto | null => {
      const imageUrl = p.thumbnail?.source;
      if (!imageUrl) return null;
      return { imageUrl, sourceUrl: p.fullurl || imageUrl, sourceTitle: p.title ?? query };
    })
    .filter((f): f is AiPhoto => f !== null);
}

/** The English Wikipedia article with exactly this title (redirects followed), if there is one. */
async function fetchWikipediaArticle(title: string): Promise<SightMatch> {
  const params = new URLSearchParams({
    action: 'query',
    format: 'json',
    origin: '*',
    titles: title,
    redirects: '1',
    prop: 'pageimages|info',
    piprop: 'thumbnail',
    pithumbsize: String(THUMB_WIDTH),
    inprop: 'url',
  });
  const data = (await fetchJson(`https://en.wikipedia.org/w/api.php?${params}`)) as {
    query?: { pages?: Record<string, WikipediaPage> };
  } | null;
  const page = Object.values(data?.query?.pages ?? {})[0];
  if (!page?.title || !page.fullurl || page.missing !== undefined) return {};
  const imageUrl = page.thumbnail?.source;
  return {
    article: { title: page.title, url: page.fullurl },
    photo: imageUrl ? { imageUrl, sourceUrl: page.fullurl, sourceTitle: page.title } : undefined,
  };
}

// a sight's photo must have been taken this close to its destination, which
// keeps the Syntagma Square of one town from showing another's
const SIGHT_RADIUS_KM = 15;

/** Commons photos matching the name among those taken around the point, best match first. */
async function searchCommonsByName(name: string, near: LatLng): Promise<AiPhoto[]> {
  const params = new URLSearchParams({
    action: 'query',
    format: 'json',
    origin: '*',
    generator: 'search',
    gsrsearch: `${name} nearcoord:${SIGHT_RADIUS_KM}km,${near.lat},${near.lng}`,
    gsrnamespace: '6',
    gsrlimit: '3',
    prop: 'imageinfo',
    iiprop: 'url',
    iiurlwidth: String(THUMB_WIDTH),
  });
  const data = (await fetchJson(`https://commons.wikimedia.org/w/api.php?${params}`)) as {
    query?: { pages?: Record<string, CommonsPage> };
  } | null;
  return commonsPhotos(byRank(data?.query?.pages));
}

// Best-effort: any failure just means fewer/no images, never a thrown error.
export async function fetchImagesForDestination(dest: Destination): Promise<AiPhoto[]> {
  const nearby = await searchCommonsNearby(dest);
  if (nearby.length > 0) return nearby;
  return searchWikipedia(dest.name, MAX_IMAGES);
}

export interface SightQuery {
  /** the sight as the traveller reads it, possibly not in English */
  name: string;
  /** title of its English Wikipedia article, as far as the model knows */
  wikiTitle?: string;
  /** the destination it belongs to */
  near: LatLng;
}

export interface SightMatch {
  photo?: AiPhoto;
  /** the Wikipedia article about the sight, when it has one */
  article?: { title: string; url: string };
}

/** Best-effort photo and Wikipedia article for one sight; empty when nothing matches. */
export async function fetchSight(query: SightQuery): Promise<SightMatch> {
  const match = query.wikiTitle ? await fetchWikipediaArticle(query.wikiTitle) : {};
  if (match.photo) return match;
  const [photo] = await searchCommonsByName(query.name, query.near);
  return { ...match, photo };
}
