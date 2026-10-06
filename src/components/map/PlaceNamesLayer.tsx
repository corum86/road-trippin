import { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { Marker, Pane, useMap, useMapEvents } from 'react-leaflet';
import L from 'leaflet';
import { useI18n } from '../../i18n/context';
import {
  cachedPlaces,
  loadPlaces,
  needsPlaces,
  placeKindsForZoom,
  type MapPlace,
  type PlaceKind,
} from '../../services/photonService';

interface PlaceNamesLayerProps {
  onSelect: (place: MapPlace) => void;
}

// Above the arrows pane (625) so a route never covers a name, below
// tooltipPane (650) so the trip's own pin labels stay on top.
const LABEL_PANE = 'vm-town-labels';
const LABEL_PANE_Z_INDEX = 640;

// wait for the map to settle (fit animation, a run of pans) before asking
const LOAD_DEBOUNCE_MS = 350;

// when names would overlap, the bigger settlement keeps its spot
const KIND_RANK: Record<PlaceKind, number> = { city: 0, town: 1, village: 2 };

// footprint of a name pill around its text (see .vm-town-label-pill), plus
// the gap kept between neighbours
const PILL_FONT = "600 11px 'Poppins', system-ui, sans-serif";
const PILL_PADDING = 20;
const PILL_HEIGHT = 24;
const PILL_GAP = 2;

let measureContext: CanvasRenderingContext2D | null | undefined;
const textWidths = new Map<string, number>();

function textWidth(text: string): number {
  let width = textWidths.get(text);
  if (width === undefined) {
    if (measureContext === undefined) measureContext = document.createElement('canvas').getContext('2d');
    if (measureContext) measureContext.font = PILL_FONT;
    width = measureContext?.measureText(text).width ?? text.length * 6.6;
    textWidths.set(text, width);
  }
  return width;
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c] ?? c);
}

// cached so re-renders hand react-leaflet the same instance (see icons.ts)
const iconCache = new Map<string, L.DivIcon>();

function placeNameIcon(place: MapPlace): L.DivIcon {
  const key = `${place.kind}|${place.name}`;
  let icon = iconCache.get(key);
  if (!icon) {
    icon = L.divIcon({
      className: `vm-town-label vm-town-label-${place.kind}`,
      html: `<span class="vm-town-label-pill">${escapeHtml(place.name)}</span>`,
      // zero-size anchor on the coordinate; the pill centres itself on it
      iconSize: [0, 0],
    });
    iconCache.set(key, icon);
  }
  return icon;
}

/**
 * Selectable names of the cities and towns in view. The basemap's own labels
 * are part of the tile images and can't be clicked, so these pills sit on top
 * of them, loaded for wherever the map is looking.
 */
export function PlaceNamesLayer({ onSelect }: PlaceNamesLayerProps) {
  const map = useMap();
  const { t, lang } = useI18n();
  // bumped when the map settles somewhere new / when more places have loaded
  const [view, setView] = useState(0);
  const [loaded, setLoaded] = useState(0);
  const [status, setStatus] = useState<'idle' | 'loading' | 'failed'>('idle');

  useMapEvents({ moveend: () => setView((n) => n + 1) });

  useEffect(() => {
    const { lat, lng } = map.getCenter();
    const center = { lat, lng };
    const radiusKm = map.distance(center, map.getBounds().getNorthEast()) / 1000;
    const zoom = map.getZoom();
    if (!needsPlaces(center, radiusKm, zoom, lang)) {
      setStatus('idle');
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      setStatus('loading');
      loadPlaces(center, radiusKm, zoom, lang, controller.signal).then(
        () => {
          setStatus('idle');
          setLoaded((n) => n + 1);
        },
        () => {
          if (controller.signal.aborted) return;
          setStatus('failed');
          // one of the requests may still have brought places
          setLoaded((n) => n + 1);
        },
      );
    }, LOAD_DEBOUNCE_MS);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [map, view, lang]);

  const visible = useMemo(() => {
    const zoom = map.getZoom();
    const kinds = placeKindsForZoom(zoom);
    const bounds = map.getBounds().pad(0.25);
    const candidates = cachedPlaces(lang)
      .filter((p) => kinds.includes(p.kind) && bounds.contains([p.location.lat, p.location.lng]))
      .sort((a, b) => KIND_RANK[a.kind] - KIND_RANK[b.kind] || a.id.localeCompare(b.id));
    const taken: L.Bounds[] = [];
    return candidates.filter((place) => {
      const { x, y } = map.project([place.location.lat, place.location.lng], zoom);
      const halfWidth = (textWidth(place.name) + PILL_PADDING + PILL_GAP) / 2;
      const halfHeight = (PILL_HEIGHT + PILL_GAP) / 2;
      const box = L.bounds([x - halfWidth, y - halfHeight], [x + halfWidth, y + halfHeight]);
      if (taken.some((other) => other.overlaps(box))) return false;
      taken.push(box);
      return true;
    });
    // view and loaded stand in for the map position and the place cache
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map, view, loaded, lang]);

  return (
    <>
      <Pane name={LABEL_PANE} style={{ zIndex: LABEL_PANE_Z_INDEX }}>
        {visible.map((place) => (
          <Marker
            key={place.id}
            position={[place.location.lat, place.location.lng]}
            icon={placeNameIcon(place)}
            eventHandlers={{ click: () => onSelect(place) }}
          />
        ))}
      </Pane>
      {status !== 'idle' &&
        createPortal(
          <div className="vm-town-labels-status" role="status">
            {t(status === 'loading' ? 'map.placesLoading' : 'map.placesFailed')}
          </div>,
          map.getContainer(),
        )}
    </>
  );
}
