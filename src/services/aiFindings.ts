import { v4 as uuidv4 } from 'uuid';
import type { Destination } from '../types/models';
import type { AiFinding } from '../types/ai';

/** A finding as one line of a destination's things to do. */
export function attractionOf(finding: AiFinding): string {
  return finding.name && finding.text ? `${finding.name}: ${finding.text}` : finding.name || finding.text;
}

/** How many photos, things to do and links the findings come to. */
export function countFindings(findings: AiFinding[]): { photos: number; facts: number; links: number } {
  return {
    photos: findings.filter((f) => f.photo).length,
    facts: findings.length,
    links: findings.filter((f) => f.link).length,
  };
}

type FindingContent = Pick<Destination, 'photos' | 'attractions' | 'links'>;

/**
 * The destination's content with the findings saved into it: each adds its
 * text, photo and link, skipping what the destination already has.
 */
export function withFindings(current: FindingContent, findings: AiFinding[]): FindingContent {
  const next = {
    photos: [...current.photos],
    attractions: [...current.attractions],
    links: [...current.links],
  };
  for (const finding of findings) {
    const line = attractionOf(finding);
    if (line && !next.attractions.includes(line)) next.attractions.push(line);
    const { photo, link } = finding;
    if (photo && !next.photos.some((p) => p.url === photo.imageUrl)) {
      next.photos.push({ id: uuidv4(), url: photo.imageUrl, caption: photo.sourceTitle });
    }
    if (link && !next.links.some((l) => l.url === link.url)) {
      next.links.push({ id: uuidv4(), label: link.label, url: link.url });
    }
  }
  return next;
}

/** "en.wikipedia.org" from a link URL: what a card shows for its link. */
export function domainOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return url;
  }
}
