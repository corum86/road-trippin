import { useLayoutEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useMap, useMapEvents } from 'react-leaflet';
import type { LatLng as LeafletLatLng, Point as LeafletPoint } from 'leaflet';
import type { Destination, MainLocation } from '../../types/models';
import { bezierControlPoint, hashString, trimQuadraticBezier } from '../../services/geo';

interface CurvedArrowsOverlayProps {
  mainLocation: MainLocation;
  destinations: Destination[];
  selectedDestinationId: string | null;
}

// Above markerPane (600) so arrows draw over the pins, below tooltipPane (650)
// and popupPane (700) so popups stay readable.
const ARROW_PANE = 'vm-arrows';
const ARROW_PANE_Z_INDEX = '625';

// Arrow thickness scales with zoom: base widths apply at REF_ZOOM and grow or
// shrink by sqrt(2) per zoom step (half the map's own 2x-per-step rate — full
// geometric scaling overwhelms the fixed-size pins within a couple of steps),
// clamped so arrows stay visible far out and reasonable close in.
const REF_ZOOM = 7;
const MIN_STROKE = 1.2;
const MAX_STROKE = 14;

// stop the arrow this many screen pixels short of the destination point, so
// the arrowhead sits just before the pin instead of underneath it
const ARROW_TIP_GAP_PX = 12;

function zoomScaleFor(zoom: number): number {
  return Math.pow(2, (zoom - REF_ZOOM) / 2);
}

export function CurvedArrowsOverlay({
  mainLocation,
  destinations,
  selectedDestinationId,
}: CurvedArrowsOverlayProps) {
  const map = useMap();
  const svgRef = useRef<SVGSVGElement>(null);
  const [recalcTick, setRecalcTick] = useState(0);

  const recalc = () => setRecalcTick((n) => n + 1);

  useMapEvents({
    // 'zoom' fires per frame during pinch-zoom and flyTo (and once as an
    // animated zoom settles), so curves track continuously there.
    zoom: recalc,
    zoomend: recalc,
    moveend: recalc,
    viewreset: recalc,
    resize: recalc,
    // Animated zooms (scroll wheel, +/- buttons) don't re-render mid-flight;
    // Leaflet instead expects layers to apply a scale/translate transform on
    // 'zoomanim', which the leaflet-zoom-animated CSS class then transitions.
    // This is what makes the arrows grow/shrink *during* the zoom.
    zoomanim: (e) => {
      const svg = svgRef.current;
      if (!svg) return;
      const nw = map.layerPointToLatLng([view.left, view.top]);
      const scale = map.getZoomScale(e.zoom);
      const offset = (
        map as unknown as {
          _latLngToNewLayerPoint: (ll: LeafletLatLng, z: number, c: LeafletLatLng) => LeafletPoint;
        }
      )._latLngToNewLayerPoint(nw, e.zoom, e.center);
      svg.style.transform = `translate3d(${offset.x}px, ${offset.y}px, 0) scale(${scale})`;
    },
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
      // shape the bow from the full curve, then cut it short of the pin
      const trimmed = trimQuadraticBezier(origin, control, end, ARROW_TIP_GAP_PX);
      return {
        id: dest.id,
        d: `M ${origin.x} ${origin.y} Q ${trimmed.control.x} ${trimmed.control.y} ${trimmed.end.x} ${trimmed.end.y}`,
        selected: dest.id === selectedDestinationId,
      };
    });
    // selected arrow last so its border/shadow draws over the others
    return [...all.filter((p) => !p.selected), ...all.filter((p) => p.selected)];
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map, mainLocation, destinations, selectedDestinationId, recalcTick]);

  // Set the settled transform imperatively, not via the style prop: after a
  // pure zoom (no pan) the translate values can be identical to the previous
  // render's, so React's style diff would skip the write and leave the
  // zoomanim handler's scale() transform stuck on the element.
  useLayoutEffect(() => {
    const svg = svgRef.current;
    if (svg) svg.style.transform = `translate3d(${view.left}px, ${view.top}px, 0)`;
  });

  // fresh on every recalcTick-triggered render
  const zoomScale = zoomScaleFor(map.getZoom());
  const shadowOffset = Math.min(1.5 * zoomScale, 4);
  const shadowBlur = Math.min(1.5 * zoomScale, 5);

  // get-or-create keeps this idempotent across re-renders and StrictMode
  let arrowPane = map.getPane(ARROW_PANE);
  if (!arrowPane) {
    arrowPane = map.createPane(ARROW_PANE);
    arrowPane.style.zIndex = ARROW_PANE_Z_INDEX;
    arrowPane.style.pointerEvents = 'none';
  }

  return createPortal(
    <svg
      ref={svgRef}
      className="vm-arrows-svg leaflet-zoom-animated"
      width={view.width}
      height={view.height}
      viewBox={`${view.left} ${view.top} ${view.width} ${view.height}`}
      style={{
        position: 'absolute',
        // transform is managed imperatively (layout effect + zoomanim handler)
        transformOrigin: '0 0',
        pointerEvents: 'none',
      }}
    >
      <defs>
        <filter id="vm-arrow-shadow" x="-20%" y="-20%" width="140%" height="140%">
          <feDropShadow
            dx="0"
            dy={shadowOffset}
            stdDeviation={shadowBlur}
            floodColor="#000"
            floodOpacity="0.35"
          />
        </filter>
        <marker
          id="vm-arrowhead"
          viewBox="0 0 10 10"
          refX="8"
          refY="5"
          markerWidth="3.5"
          markerHeight="3.5"
          orient="auto-start-reverse"
        >
          <path d="M0,0 L10,5 L0,10 z" fill="#e0563f" stroke="#9c3423" strokeWidth="0.6" />
        </marker>
        <marker
          id="vm-arrowhead-selected"
          viewBox="0 0 10 10"
          refX="8"
          refY="5"
          markerWidth="4"
          markerHeight="4"
          orient="auto-start-reverse"
        >
          <path d="M0,0 L10,5 L0,10 z" fill="#2563eb" stroke="#1e40af" strokeWidth="0.6" />
        </marker>
      </defs>
      <g filter="url(#vm-arrow-shadow)">
        {paths.map((p) => {
          const baseWidth = p.selected ? 3.5 : 2.5;
          const strokeWidth = Math.min(Math.max(baseWidth * zoomScale, MIN_STROKE), MAX_STROKE);
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
