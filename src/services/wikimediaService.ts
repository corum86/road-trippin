import { v4 as uuidv4 } from 'uuid';
import type { Destination } from '../types/models';
import type { AiImageFinding } from '../types/ai';

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
  imageinfo?: CommonsImageInfo[];
}

interface WikipediaPage {
  title?: string;
  fullurl?: string;
  thumbnail?: { source?: string };
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

async function searchCommonsNearby(dest: Destination): Promise<AiImageFinding[]> {
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

  const pages = Object.values(data?.query?.pages ?? {});
  return pages
    .filter((p) => PHOTO_EXTENSIONS.test(p.title ?? ''))
    .map((p): AiImageFinding | null => {
      const info = p.imageinfo?.[0];
      const imageUrl = info?.thumburl || info?.url;
      if (!imageUrl) return null;
      return {
        id: uuidv4(),
        kind: 'image',
        imageUrl,
        sourceUrl: info?.descriptionurl || imageUrl,
        sourceTitle: cleanFileTitle(p.title),
        added: false,
      };
    })
    .filter((f): f is AiImageFinding => f !== null)
    .slice(0, MAX_IMAGES);
}

async function searchWikipediaByName(dest: Destination): Promise<AiImageFinding[]> {
  const params = new URLSearchParams({
    action: 'query',
    format: 'json',
    origin: '*',
    generator: 'search',
    gsrsearch: dest.name,
    gsrlimit: String(MAX_IMAGES),
    prop: 'pageimages|info',
    piprop: 'thumbnail',
    pithumbsize: String(THUMB_WIDTH),
    inprop: 'url',
  });
  const data = (await fetchJson(`https://en.wikipedia.org/w/api.php?${params}`)) as {
    query?: { pages?: Record<string, WikipediaPage> };
  } | null;

  const pages = Object.values(data?.query?.pages ?? {});
  return pages
    .map((p): AiImageFinding | null => {
      const imageUrl = p.thumbnail?.source;
      if (!imageUrl) return null;
      return {
        id: uuidv4(),
        kind: 'image',
        imageUrl,
        sourceUrl: p.fullurl || imageUrl,
        sourceTitle: p.title ?? dest.name,
        added: false,
      };
    })
    .filter((f): f is AiImageFinding => f !== null)
    .slice(0, MAX_IMAGES);
}

// Best-effort: any failure just means fewer/no images, never a thrown error.
export async function fetchImagesForDestination(dest: Destination): Promise<AiImageFinding[]> {
  const nearby = await searchCommonsNearby(dest);
  if (nearby.length > 0) return nearby;
  return searchWikipediaByName(dest);
}
