import { useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { useMap, useMapEvents } from 'react-leaflet';
import type { Destination, MainLocation } from '../../types/models';
import { bezierControlPoint, hashString } from '../../services/geo';

interface CurvedArrowsOverlayProps {
  mainLocation: MainLocation;
  destinations: Destination[];
  selectedDestinationId: string | null;
}

// Above markerPane (600) so arrows draw over the pins, below tooltipPane (650)
// and popupPane (700) so popups stay readable.
const ARROW_PANE = 'vm-arrows';
const ARROW_PANE_Z_INDEX = '625';

export function CurvedArrowsOverlay({
  mainLocation,
  destinations,
  selectedDestinationId,
}: CurvedArrowsOverlayProps) {
  const map = useMap();
  const [recalcTick, setRecalcTick] = useState(0);

  useMapEvents({
    zoomend: () => setRecalcTick((n) => n + 1),
    moveend: () => setRecalcTick((n) => n + 1),
    viewreset: () => setRecalcTick((n) => n + 1),
    resize: () => setRecalcTick((n) => n + 1),
  });

  // Size and place the SVG the way Leaflet's own renderer does: cover the
  // current view (plus padding so curves survive panning until the next
  // moveend recalc) and map its viewBox to layer coordinates, so paths can
  // be written directly in latLngToLayerPoint() space.
  const view = useMemo(() => {
    const size = map.getSize();
    const padX = size.x / 2;
    const padY = size.y / 2;
    const topLeft = map.containerPointToLayerPoint([-padX, -padY]);
    return {
      left: topLeft.x,
      top: topLeft.y,
      width: size.x + padX * 2,
      height: size.y + padY * 2,
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map, recalcTick]);

  const paths = useMemo(() => {
    const origin = map.latLngToLayerPoint([
      mainLocation.location.lat,
      mainLocation.location.lng,
    ]);
    const all = destinations.map((dest) => {
      const end = map.latLngToLayerPoint([dest.location.lat, dest.location.lng]);
      // per-destination bow side and strength, hashed from the id so the
      // curve shape is stable across renders, reloads, and unrelated edits
      const h = hashString(dest.id);
      const side = h % 2 === 0 ? 1 : -1;
      const bow = side * (0.16 + (h % 7) * 0.015);
      const control = bezierControlPoint(origin, end, bow);
      return {
        id: dest.id,
        d: `M ${origin.x} ${origin.y} Q ${control.x} ${control.y} ${end.x} ${end.y}`,
        selected: dest.id === selectedDestinationId,
      };
    });
    // selected arrow last so its border/shadow draws over the others
    return [...all.filter((p) => !p.selected), ...all.filter((p) => p.selected)];
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map, mainLocation, destinations, selectedDestinationId, recalcTick]);

  // get-or-create keeps this idempotent across re-renders and StrictMode
  let arrowPane = map.getPane(ARROW_PANE);
  if (!arrowPane) {
    arrowPane = map.createPane(ARROW_PANE);
    arrowPane.style.zIndex = ARROW_PANE_Z_INDEX;
    arrowPane.style.pointerEvents = 'none';
  }

  return createPortal(
    <svg
      className="vm-arrows-svg"
      width={view.width}
      height={view.height}
      viewBox={`${view.left} ${view.top} ${view.width} ${view.height}`}
      style={{
        position: 'absolute',
        left: view.left,
        top: view.top,
        pointerEvents: 'none',
      }}
    >
      <defs>
        <filter id="vm-arrow-shadow" x="-20%" y="-20%" width="140%" height="140%">
          <feDropShadow dx="0" dy="1.5" stdDeviation="1.5" floodColor="#000" floodOpacity="0.35" />
        </filter>
        <marker
          id="vm-arrowhead"
          viewBox="0 0 10 10"
          refX="8"
          refY="5"
          markerWidth="7"
          markerHeight="7"
          orient="auto-start-reverse"
        >
          <path d="M0,0 L10,5 L0,10 z" fill="#e0563f" stroke="#9c3423" strokeWidth="0.6" />
        </marker>
        <marker
          id="vm-arrowhead-selected"
          viewBox="0 0 10 10"
          refX="8"
          refY="5"
          markerWidth="8"
          markerHeight="8"
          orient="auto-start-reverse"
        >
          <path d="M0,0 L10,5 L0,10 z" fill="#2563eb" stroke="#1e40af" strokeWidth="0.6" />
        </marker>
      </defs>
      <g filter="url(#vm-arrow-shadow)">
        {paths.map((p) => {
          const strokeWidth = p.selected ? 3.5 : 2.5;
          return (
            <g key={p.id} opacity={p.selected ? 1 : 0.85}>
              {/* casing: 1px border on each side of the colored stroke */}
              <path
                d={p.d}
                fill="none"
                stroke={p.selected ? '#1e40af' : '#9c3423'}
                strokeWidth={strokeWidth + 2}
                strokeLinecap="round"
              />
              <path
                d={p.d}
                fill="none"
                stroke={p.selected ? '#2563eb' : '#e0563f'}
                strokeWidth={strokeWidth}
                strokeLinecap="round"
                markerEnd={p.selected ? 'url(#vm-arrowhead-selected)' : 'url(#vm-arrowhead)'}
              />
            </g>
          );
        })}
      </g>
    </svg>,
    arrowPane,
  );
}
