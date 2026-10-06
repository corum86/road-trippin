import { v4 as uuidv4 } from 'uuid';
import type {
  BudgetLevel,
  Destination,
  MustHave,
  PlaceSuggestion,
  PlannedPlace,
  TravelGroup,
  TravelStyle,
} from '../../types/models';
import type { DestinationAiResult } from '../../types/ai';
import type { TranslationKey } from '../../i18n/translations';
import type { TranslateFn } from '../../i18n/context';
import { formatDuration } from '../../services/routeFormat';

/** Wizard steps in order. Research and per-place review share the "Review" segment. */
export const STEPS = ['dates', 'drive', 'group', 'style', 'extras', 'places', 'research', 'review', 'plan'] as const;
export type WizardStep = (typeof STEPS)[number];

export const SEGMENTS: TranslationKey[] = [
  'wizard.seg.dates',
  'wizard.seg.drive',
  'wizard.seg.group',
  'wizard.seg.style',
  'wizard.seg.extras',
  'wizard.seg.places',
  'wizard.seg.review',
];

/** Progress segment lit for each step (the plan step fills them all). */
export function segmentOf(step: WizardStep): number {
  const index = STEPS.indexOf(step);
  return step === 'review' ? 6 : step === 'plan' ? SEGMENTS.length : Math.min(index, 6);
}

export const LENGTH_OPTIONS: Array<{ days: number; labelKey: TranslationKey }> = [
  { days: 3, labelKey: 'wizard.length.weekend' },
  { days: 5, labelKey: 'wizard.length.5' },
  { days: 7, labelKey: 'wizard.length.week' },
  { days: 10, labelKey: 'wizard.length.10' },
];

/** One-way drive limits; null means distance doesn't matter. */
export const DRIVE_OPTIONS: Array<{ minutes: number | null; labelKey: TranslationKey }> = [
  { minutes: 30, labelKey: 'wizard.drive.30' },
  { minutes: 60, labelKey: 'wizard.drive.60' },
  { minutes: 90, labelKey: 'wizard.drive.90' },
  { minutes: 120, labelKey: 'wizard.drive.120' },
  { minutes: null, labelKey: 'wizard.drive.any' },
];

export const GROUP_OPTIONS: Array<{ id: TravelGroup; icon: string }> = [
  { id: 'couple', icon: 'favorite' },
  { id: 'family', icon: 'family_restroom' },
  { id: 'friends', icon: 'groups' },
];

export const STYLE_OPTIONS: Array<{ id: TravelStyle; icon: string }> = [
  { id: 'relaxed', icon: 'beach_access' },
  { id: 'active', icon: 'hiking' },
  { id: 'sightseeing', icon: 'photo_camera' },
  { id: 'culture', icon: 'account_balance' },
  { id: 'food', icon: 'restaurant' },
  { id: 'nature', icon: 'forest' },
];

export const MUST_HAVE_OPTIONS: Array<{ id: MustHave; icon: string }> = [
  { id: 'beach', icon: 'beach_access' },
  { id: 'food', icon: 'restaurant' },
  { id: 'kids', icon: 'child_care' },
  { id: 'nightlife', icon: 'nightlife' },
];

export const BUDGET_OPTIONS: Array<{ level: BudgetLevel; symbol: string }> = [
  { level: 1, symbol: '€' },
  { level: 2, symbol: '€€' },
  { level: 3, symbol: '€€€' },
];

/** The answers so far. `drive` is undefined until chosen; null = no limit. */
export interface WizardAnswers {
  start: string | null;
  end: string | null;
  drive: number | null | undefined;
  ferry: boolean;
  group: TravelGroup | null;
  styles: TravelStyle[];
  budget: BudgetLevel | null;
  mustHaves: MustHave[];
}

export type ResearchState =
  | { status: 'queued' }
  | { status: 'loading' }
  | { status: 'done'; result: DestinationAiResult }
  | { status: 'error'; error: string };

/** Which findings are ticked to be saved, per tab. */
export interface FindingPicks {
  photos: boolean[];
  facts: boolean[];
  links: boolean[];
}

/** Ticked by default: the first 3 photos, 4 facts and 2 links. */
export function defaultPicks(result: DestinationAiResult): FindingPicks {
  return {
    photos: result.images.map((_, i) => i < 3),
    facts: result.texts.map((_, i) => i < 4),
    links: result.links.map((_, i) => i < 2),
  };
}

/** The research service works on destinations; present a suggestion as one. */
export function suggestionAsDestination(s: PlaceSuggestion): Destination {
  return {
    id: s.id,
    name: s.name,
    location: s.location,
    attractions: [],
    photos: [],
    links: [],
    status: 'planned',
    favorite: false,
  };
}

/** What gets saved to Places for a picked suggestion: its ticked findings. */
export function toPlannedPlace(
  s: PlaceSuggestion,
  research: ResearchState | undefined,
  picks: FindingPicks | undefined,
): PlannedPlace {
  const result = research?.status === 'done' ? research.result : null;
  const ticked = <T>(items: T[], flags: boolean[] | undefined) => items.filter((_, i) => flags?.[i]);
  return {
    id: s.id,
    name: s.name,
    location: s.location,
    notes: s.blurb || undefined,
    routeInfo: {
      distanceMeters: s.distanceKm * 1000,
      durationSeconds: s.driveMinutes * 60,
      source: s.estimated ? 'straight-line-estimate' : 'osrm',
      fetchedAt: new Date().toISOString(),
    },
    photos: result
      ? ticked(result.images, picks?.photos).map((img) => ({ id: uuidv4(), url: img.imageUrl, caption: img.sourceTitle }))
      : [],
    attractions: result ? ticked(result.texts, picks?.facts).map((f) => f.fact) : [],
    links: result ? ticked(result.links, picks?.links).map((l) => ({ id: uuidv4(), label: l.label, url: l.url })) : [],
  };
}

/** "en.wikipedia.org" from a link URL, for the review list. */
export function domainOf(url: string): string {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return url;
  }
}

/** "55 min drive · 50 km", or "… incl. ferry …" */
export function driveLine(s: PlaceSuggestion, t: TranslateFn): string {
  return t(s.ferry ? 'wizard.ferryFmt' : 'wizard.driveFmt', {
    t: formatDuration(s.driveMinutes * 60, t),
    km: Math.round(s.distanceKm),
  });
}
