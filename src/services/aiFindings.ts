import { v4 as uuidv4 } from 'uuid';
import type { Destination } from '../types/models';
import type { AiFinding } from '../types/ai';

// letters of scripts neither app language is written in: Cyrillic, Hebrew,
// Arabic, Indic, CJK (with its punctuation and full-width forms), Hangul
const FOREIGN_SCRIPT = /[\u0400-\u052f\u0590-\u08ff\u0900-\u0dff\u3000-\u30ff\u3400-\u9fff\uac00-\ud7af\uff00-\uffef]/;
const GREEK_LETTER = /[\u0370-\u03ff\u1f00-\u1fff]/;
const LATIN_LETTER = /[A-Za-z\u00c0-\u024f]/;
const WORD = /[A-Za-z\u00c0-\u024f\u0370-\u03ff\u1f00-\u1fff]+/g;

/**
 * True when text the model wrote in `lang` came out corrupted, which the
 * small Gemini models do now and then: stray letters of another script, or,
 * in Greek, words with Latin letters in them ("Λitharitsia") up to whole
 * sentences of Latin look-alikes. One mixed word is let through; a Latin
 * name inside Greek text is fine.
 */
export function looksGarbled(text: string, lang: string): boolean {
  if (FOREIGN_SCRIPT.test(text)) return true;
  if (lang !== 'el') return false;
  const words = text.match(WORD) ?? [];
  const mixed = words.filter((word) => GREEK_LETTER.test(word) && LATIN_LETTER.test(word)).length;
  if (mixed >= 2) return true;
  let greek = 0;
  let latin = 0;
  for (const ch of words.join('')) {
    if (GREEK_LETTER.test(ch)) greek += 1;
    else latin += 1;
  }
  // long enough to judge, and mostly not Greek
  return greek + latin >= 20 && latin > (greek + latin) * 0.4;
}

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
