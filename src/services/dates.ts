import type { Lang } from '../i18n/translations';

// Calendar dates are plain ISO strings (YYYY-MM-DD). All arithmetic and
// formatting runs in UTC so a date never shifts with the viewer's time zone.

const LOCALES: Record<Lang, string> = { en: 'en-GB', el: 'el-GR' };
const MS_PER_DAY = 864e5;

/** Today as a local ISO date (YYYY-MM-DD), not shifted to UTC. */
export function todayIso(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export function isIsoDate(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(parseIso(value).getTime());
}

function parseIso(iso: string): Date {
  const [y, m, d] = iso.slice(0, 10).split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d));
}

function toIso(date: Date): string {
  return date.toISOString().slice(0, 10);
}

export function addDays(iso: string, days: number): string {
  const date = parseIso(iso);
  date.setUTCDate(date.getUTCDate() + days);
  return toIso(date);
}

/** Whole days from `a` to `b` (negative when `b` is earlier). */
export function diffDays(a: string, b: string): number {
  return Math.round((parseIso(b).getTime() - parseIso(a).getTime()) / MS_PER_DAY);
}

/** Shift a month key (YYYY-MM) by `months`. */
export function addMonths(month: string, months: number): string {
  const [y, m] = month.split('-').map(Number);
  return toIso(new Date(Date.UTC(y, m - 1 + months, 1))).slice(0, 7);
}

/** Monday-first weekday index (0 = Monday) of an ISO date. */
export function weekdayIndex(iso: string): number {
  return (parseIso(iso).getUTCDay() + 6) % 7;
}

export function daysInMonth(month: string): number {
  const [y, m] = month.split('-').map(Number);
  return new Date(Date.UTC(y, m, 0)).getUTCDate();
}

export function formatDate(iso: string, lang: Lang, options: Intl.DateTimeFormatOptions): string {
  if (!isIsoDate(iso.slice(0, 10))) return iso;
  return parseIso(iso).toLocaleDateString(LOCALES[lang], { timeZone: 'UTC', ...options });
}

/** "Tue 14 Jul" */
export function formatDayLabel(iso: string, lang: Lang): string {
  return formatDate(iso, lang, { weekday: 'short', day: 'numeric', month: 'short' });
}

/** "14 Jul" */
export function formatShortDate(iso: string, lang: Lang): string {
  return formatDate(iso, lang, { day: 'numeric', month: 'short' });
}

/** "13–18 Jul 2026", across months "30 Jun–4 Jul 2026", a single day "13 Jul 2026" */
export function formatDateRange(startIso: string, endIso: string, lang: Lang): string {
  const year = formatDate(endIso, lang, { year: 'numeric' });
  if (startIso === endIso) return `${formatShortDate(startIso, lang)} ${year}`;
  const sameMonth = startIso.slice(0, 7) === endIso.slice(0, 7);
  const start = sameMonth ? formatDate(startIso, lang, { day: 'numeric' }) : formatShortDate(startIso, lang);
  return `${start}–${formatShortDate(endIso, lang)} ${year}`;
}
