import { useEffect, useRef } from 'react';
import { useMapDataStore } from '../store/mapDataStore';
import { fetchRoute } from '../services/osrmService';
import type { VacationMapData } from '../types/models';

/**
 * Routes mode needs road geometry for every destination. Older cached
 * routeInfo (from before geometry was stored) has distance/time but no
 * path, so backfill those too. In-flight guard prevents duplicate calls
 * to the public OSRM server while responses are pending.
 */
export function useRouteGeometryBackfill(enabled: boolean, data: VacationMapData | null) {
  const setRouteInfo = useMapDataStore((s) => s.setRouteInfo);
  const inFlight = useRef(new Set<string>());

  useEffect(() => {
    if (!enabled || !data) return;
    for (const dest of data.destinations) {
      if (dest.routeInfo?.geometry || inFlight.current.has(dest.id)) continue;
      inFlight.current.add(dest.id);
      fetchRoute(data.mainLocation.location, dest.location)
        .then((info) => setRouteInfo(dest.id, info))
        .finally(() => inFlight.current.delete(dest.id));
    }
  }, [enabled, data, setRouteInfo]);
}
