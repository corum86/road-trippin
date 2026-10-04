import { useEffect, useRef } from 'react';
import { useMap } from 'react-leaflet';
import L from 'leaflet';
import type { LatLng } from '../../types/models';

export interface MapPadding {
  top: number;
  right: number;
  bottom: number;
  left: number;
}

function paddingOptions(p: MapPadding): L.FitBoundsOptions {
  return { paddingTopLeft: [p.left, p.top], paddingBottomRight: [p.right, p.bottom] };
}

interface FitToPointsProps {
  points: LatLng[];
  padding: MapPadding;
  /** refit whenever this changes (e.g. the set of locations) */
  fitKey: string;
  /** wait before refitting, e.g. for a resizing side panel to settle */
  delayMs?: number;
}

/** Fits the view to the given points once per `fitKey`, leaving room for overlaid UI. */
export function FitToPoints({ points, padding, fitKey, delayMs = 0 }: FitToPointsProps) {
  const map = useMap();
  const hasFitted = useRef(false);
  useEffect(() => {
    if (points.length === 0) return;
    const fit = () => {
      map.invalidateSize();
      const bounds = L.latLngBounds(points.map((p) => [p.lat, p.lng] as [number, number]));
      // jump into place on mount, glide on later refits
      map.fitBounds(bounds, { ...paddingOptions(padding), maxZoom: 12, animate: hasFitted.current });
      hasFitted.current = true;
    };
    if (delayMs <= 0 || !hasFitted.current) {
      fit();
      return;
    }
    const timer = window.setTimeout(fit, delayMs);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map, fitKey]);
  return null;
}

// tiles fade in over 200ms after loading; let that finish before capturing
const TILE_FADE_MS = 300;
const TILES_TIMEOUT_MS = 10000;

/**
 * Calls `onLoaded` once the tile layer has finished loading the current view.
 * Place it after FitToPoints so it sees the fitted view (sibling effects run
 * in order).
 */
export function WhenTilesLoaded({ onLoaded }: { onLoaded: () => void }) {
  const map = useMap();
  useEffect(() => {
    let done = false;
    let fadeTimer: number | undefined;
    const finish = () => {
      if (done) return;
      done = true;
      fadeTimer = window.setTimeout(onLoaded, TILE_FADE_MS);
    };
    const tileLayers: L.TileLayer[] = [];
    map.eachLayer((layer) => {
      if (layer instanceof L.TileLayer) tileLayers.push(layer);
    });
    const pending = tileLayers.filter((layer) => layer.isLoading());
    let remaining = pending.length;
    const onLayerLoad = () => {
      remaining -= 1;
      if (remaining <= 0) finish();
    };
    pending.forEach((layer) => layer.once('load', onLayerLoad));
    if (remaining === 0) finish();
    // never hang the export on a stuck tile; capture what we have
    const timeout = window.setTimeout(finish, TILES_TIMEOUT_MS);
    return () => {
      done = true;
      window.clearTimeout(timeout);
      window.clearTimeout(fadeTimer);
      pending.forEach((layer) => layer.off('load', onLayerLoad));
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map]);
  return null;
}

interface KeepInViewProps {
  point: LatLng | null;
  padding: MapPadding;
}

/** Pans just enough to keep `point` clear of overlaid UI whenever it changes. */
export function KeepInView({ point, padding }: KeepInViewProps) {
  const map = useMap();
  useEffect(() => {
    if (!point) return;
    map.panInside([point.lat, point.lng], paddingOptions(padding));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map, point?.lat, point?.lng]);
  return null;
}
