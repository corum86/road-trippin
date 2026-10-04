import type { Lang } from '../i18n/translations';

const LOCALES: Record<Lang, string> = { en: 'en-GB', el: 'el-GR' };

/** Today as a local ISO date (YYYY-MM-DD), not shifted to UTC. */
export function todayIso(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** Parse YYYY-MM-DD as a local date; `new Date(iso)` would read it as UTC midnight. */
function parseIsoDate(iso: string): Date | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  if (!m) return null;
  return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
}

/** "Tue 14 Jul" */
export function formatDayLabel(iso: string, lang: Lang): string {
  const date = parseIsoDate(iso);
  if (!date) return iso;
  return new Intl.DateTimeFormat(LOCALES[lang], {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
  }).format(date);
}

/** "13–18 Jul 2026" */
export function formatDateRange(startIso: string, endIso: string, lang: Lang): string {
  const start = parseIsoDate(startIso);
  const end = parseIsoDate(endIso);
  if (!start || !end) return '';
  return new Intl.DateTimeFormat(LOCALES[lang], {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  }).formatRange(start, end);
}
