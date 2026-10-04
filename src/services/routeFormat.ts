import type { TranslateFn } from '../i18n/context';
import type { RouteInfo } from '../types/models';

export function formatDistance(meters: number, t: TranslateFn): string {
  return `${(meters / 1000).toFixed(1)} ${t('units.km')}`;
}

export function formatDuration(seconds: number, t: TranslateFn): string {
  const totalMinutes = Math.round(seconds / 60);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours === 0) return `${minutes} ${t('units.minutes')}`;
  return `${hours} ${t('units.hours')} ${minutes} ${t('units.minutes')}`;
}

export function isEstimate(info: RouteInfo): boolean {
  return info.source === 'straight-line-estimate';
}

/** Short " (est.)" suffix for straight-line estimates, empty for real routes. */
export function estimateSuffix(info: RouteInfo, t: TranslateFn): string {
  return isEstimate(info) ? ` (${t('routes.estimatedShort')})` : '';
}

/** "24.9 km · 30 min", plus " (est.)" for straight-line estimates. */
export function formatRouteSummary(info: RouteInfo, t: TranslateFn): string {
  return `${formatDistance(info.distanceMeters, t)} · ${formatDuration(info.durationSeconds, t)}${estimateSuffix(info, t)}`;
}
