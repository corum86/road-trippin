import { useEffect, useState } from 'react';
import type { TranslateFn } from '../../i18n/context';
import type { Destination, MainLocation, Photo, VacationMapData } from '../../types/models';
import type { Lang } from '../../i18n/translations';
import { formatDateRange } from '../../services/dates';
import { tripDayCount } from '../../services/tripPlan';
import { estimateFromStraightLine, fetchRoute } from '../../services/osrmService';
import { formatRouteSummary } from '../../services/routeFormat';
import { useMapDataStore } from '../../store/mapDataStore';

/**
 * Route line for list rows and cards. Falls back to a straight-line estimate
 * until OSRM has been asked (on first detail open). Empty while there is no
 * home base to measure from.
 */
export function routeText(dest: Destination, home: MainLocation | null, t: TranslateFn): string {
  const route = dest.routeInfo ?? (home ? estimateFromStraightLine(home.location, dest.location) : null);
  return route ? formatRouteSummary(route, t) : '';
}

export function statusLabel(dest: Destination, t: TranslateFn): string {
  return dest.status === 'visited' ? t('status.visited') : t('status.planned');
}

/** Reference photos first, then the traveller's own. */
export function allPhotos(dest: Destination): Photo[] {
  return [...dest.photos, ...(dest.visit?.photos ?? [])];
}

/** Fetches the OSRM route when the destination has none cached; returns whether it is loading. */
export function useEnsureRoute(dest: Destination | undefined, home: MainLocation | null): boolean {
  const setRouteInfo = useMapDataStore((s) => s.setRouteInfo);
  const [loading, setLoading] = useState(false);
  const needsRoute = !!dest && !!home && !dest.routeInfo;

  useEffect(() => {
    if (!dest || !home || !needsRoute) return;
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
  const { trip, destinations } = data;
  return {
    dayCount: tripDayCount(trip),
    total: destinations.length,
    visited: destinations.filter((d) => d.status === 'visited').length,
    /** null while the trip has no dates */
    dateRange: trip.startDate && trip.endDate ? formatDateRange(trip.startDate, trip.endDate, lang) : null,
  };
}

/** "6 days" / "1 day" */
export function dayCountLabel(count: number, t: TranslateFn): string {
  return count === 1 ? t('trip.oneDay') : t('trip.nDays', { n: count });
}
