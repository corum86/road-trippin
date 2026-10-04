import { useEffect, useState } from 'react';
import type { TranslateFn } from '../../i18n/context';
import type { Destination, MainLocation, Photo, VacationMapData } from '../../types/models';
import type { Lang } from '../../i18n/translations';
import { formatDateRange } from '../../services/dates';
import { estimateFromStraightLine, fetchRoute } from '../../services/osrmService';
import { formatRouteSummary } from '../../services/routeFormat';
import { useMapDataStore } from '../../store/mapDataStore';

/**
 * Route line for list rows and cards. Falls back to a straight-line estimate
 * until OSRM has been asked (on first detail open).
 */
export function routeText(dest: Destination, home: MainLocation, t: TranslateFn): string {
  return formatRouteSummary(dest.routeInfo ?? estimateFromStraightLine(home.location, dest.location), t);
}

export function statusLabel(dest: Destination, t: TranslateFn): string {
  return dest.status === 'visited' ? t('status.visited') : t('status.planned');
}

/** Reference photos first, then the traveller's own. */
export function allPhotos(dest: Destination): Photo[] {
  return [...dest.photos, ...(dest.visit?.photos ?? [])];
}

/** Fetches the OSRM route when the destination has none cached; returns whether it is loading. */
export function useEnsureRoute(dest: Destination | undefined, home: MainLocation): boolean {
  const setRouteInfo = useMapDataStore((s) => s.setRouteInfo);
  const [loading, setLoading] = useState(false);
  const needsRoute = !!dest && !dest.routeInfo;

  useEffect(() => {
    if (!dest || !needsRoute) return;
    let cancelled = false;
    setLoading(true);
    fetchRoute(home.location, dest.location)
      .then((info) => {
        if (!cancelled) setRouteInfo(dest.id, info);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // refetch only when the route has been invalidated, not on every edit
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dest?.id, needsRoute]);

  return loading;
}

/** Trip-wide numbers shared by the Trip screen and the Places header. */
export function tripSummary(data: VacationMapData, lang: Lang) {
  const days = [...data.itinerary].sort((a, b) => a.date.localeCompare(b.date));
  return {
    days,
    total: data.destinations.length,
    visited: data.destinations.filter((d) => d.status === 'visited').length,
    dateRange: days.length > 0 ? formatDateRange(days[0].date, days[days.length - 1].date, lang) : '',
  };
}
