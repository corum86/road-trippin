import { v4 as uuidv4 } from 'uuid';
import type {
  BudgetLevel,
  Destination,
  MainLocation,
  MustHave,
  PlaceSuggestion,
  TravelGroup,
  TravelStyle,
} from '../types/models';
import { fetchPlaceSuggestions } from './geminiService';
import { haversineDistanceMeters } from './geo';
import { fetchDrivesFrom } from './osrmService';
import { sanitizeSuggestions } from './tripPlan';

// the model sometimes invents coordinates; drop anything implausibly far away
const MAX_STRAIGHT_LINE_KM = 400;
// a suggestion this close to a saved place is that place
const SAME_PLACE_KM = 1.5;

function normalizeName(name: string): string {
  return name.trim().toLowerCase();
}

/**
 * Day-trip suggestions around the home base: ideas from Gemini, matched to
 * already-saved places (so they keep the saved id), with real drive times
 * from one OSRM table request.
 */
export async function loadSuggestions(
  home: MainLocation,
  destinations: Destination[],
  lang: string,
  dates?: { startDate: string; endDate: string },
): Promise<PlaceSuggestion[]> {
  const raw = await fetchPlaceSuggestions({
    homeName: home.name,
    home: home.location,
    startDate: dates?.startDate,
    endDate: dates?.endDate,
    lang,
  });

  const seenNames = new Set<string>();
  const candidates = raw.filter((s) => {
    const key = normalizeName(s.name);
    const km = haversineDistanceMeters(home.location, { lat: s.lat, lng: s.lng }) / 1000;
    if (seenNames.has(key) || km > MAX_STRAIGHT_LINE_KM) return false;
    seenNames.add(key);
    return true;
  });

  const drives = await fetchDrivesFrom(
    home.location,
    candidates.map((s) => ({ lat: s.lat, lng: s.lng })),
  );

  return sanitizeSuggestions(
    candidates.map((s, i) => {
      const location = { lat: s.lat, lng: s.lng };
      const saved = destinations.find(
        (d) =>
          normalizeName(d.name) === normalizeName(s.name) ||
          haversineDistanceMeters(d.location, location) / 1000 < SAME_PLACE_KM,
      );
      return {
        id: saved?.id ?? uuidv4(),
        name: s.name,
        location,
        blurb: s.blurb,
        styles: s.tags,
        groups: s.groups,
        budget: Math.min(Math.max(Math.round(s.budget), 1), 3),
        mustHaves: s.mustHaves,
        ferry: s.ferry,
        driveMinutes: drives[i].minutes,
        distanceKm: drives[i].km,
        estimated: drives[i].estimated,
      };
    }),
  );
}

/** Within the one-way drive limit (null = any), and not by ferry unless allowed. */
export function isReachable(s: PlaceSuggestion, maxDriveMinutes: number | null, ferry: boolean): boolean {
  return (maxDriveMinutes === null || s.driveMinutes <= maxDriveMinutes) && (ferry || !s.ferry);
}

/** An answer a suggestion matched, shown as a "why" tag. */
export type MatchReason =
  | { kind: 'style'; value: TravelStyle }
  | { kind: 'group'; value: TravelGroup }
  | { kind: 'must'; value: MustHave };

export interface RankedSuggestion {
  suggestion: PlaceSuggestion;
  score: number;
  reasons: MatchReason[];
}

/**
 * Best matches first: +2 per matched style, +2 if the group fits, +2 per
 * matched must-have, +1 if within budget. Ties go to the shorter drive.
 */
export function rankSuggestions(
  suggestions: PlaceSuggestion[],
  prefs: { styles: TravelStyle[]; group: TravelGroup | null; mustHaves: MustHave[]; budget: BudgetLevel | null },
): RankedSuggestion[] {
  return suggestions
    .map((suggestion) => {
      const reasons: MatchReason[] = [];
      let score = 0;
      for (const style of prefs.styles) {
        if (suggestion.styles.includes(style)) {
          score += 2;
          reasons.push({ kind: 'style', value: style });
        }
      }
      if (prefs.group && suggestion.groups.includes(prefs.group)) {
        score += 2;
        reasons.push({ kind: 'group', value: prefs.group });
      }
      for (const must of prefs.mustHaves) {
        if (suggestion.mustHaves.includes(must)) {
          score += 2;
          reasons.push({ kind: 'must', value: must });
        }
      }
      if (prefs.budget !== null && suggestion.budget <= prefs.budget) score += 1;
      return { suggestion, score, reasons };
    })
    .sort((a, b) => b.score - a.score || a.suggestion.driveMinutes - b.suggestion.driveMinutes);
}

/** How many places fit a trip of `days` days comfortably. */
export function recommendedPlaceCount(days: number): number {
  return Math.max(1, Math.round(Math.max(days, 1) * 0.67));
}
