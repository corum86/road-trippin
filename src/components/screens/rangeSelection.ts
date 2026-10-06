import { clampTripEnd } from '../../services/tripPlan';

/** A date range being picked on the calendar; `end` is null until the second tap. */
export interface DateRangeSelection {
  start: string | null;
  end: string | null;
}

/**
 * The calendar's tap logic: the first tap sets the start, the second the end
 * (a date before the start replaces the start instead), and a tap after both
 * are set starts over.
 */
export function pickRangeDate(range: DateRangeSelection, date: string): DateRangeSelection {
  if (!range.start || range.end) return { start: date, end: null };
  if (date < range.start) return { start: date, end: null };
  return { start: range.start, end: date };
}

/** The effective last day: a lone start is a one-day trip; capped at the maximum length. */
export function rangeEndOf(range: DateRangeSelection): string | null {
  return range.start ? clampTripEnd(range.start, range.end ?? range.start) : null;
}
