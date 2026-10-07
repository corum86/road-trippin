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
  /** stroke widths at REF_ZOOM for [other, selected] arrows */
  baseStrokeWidths?: [number, number];
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
const MIN_STROKE = 0.8;
const MAX_STROKE = 6;

// arrowhead size in stroke widths for [other, selected] arrows
const HEAD_SIZES: [number, number] = [4.5, 5];

// The arrow reads as an arc lifted off the map; its shadow lies on the ground
// below it: the same trip drawn almost straight, with this share of the bow.
const SHADOW_BOW_SHARE = 0.2;
const SHADOW_OPACITY = 0.16;

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
  baseStrokeWidths = [1.2, 1.7],
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
      const shadow = trimQuadraticBezier(
        origin,
        bezierControlPoint(origin, end, bow * SHADOW_BOW_SHARE),
        end,
        ARROW_TIP_GAP_PX,
      );
      return {
        id: dest.id,
        d: `M ${origin.x} ${origin.y} Q ${trimmed.control.x} ${trimmed.control.y} ${trimmed.end.x} ${trimmed.end.y}`,
        shadowD: `M ${origin.x} ${origin.y} Q ${shadow.control.x} ${shadow.control.y} ${shadow.end.x} ${shadow.end.y}`,
        selected: dest.id === selectedDestinationId,
      };
    });
    // selected arrow last so it draws over the others
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
  const shadowBlur = Math.min(0.5 * zoomScale, 1.5);
  const strokeWidthOf = (selected: boolean) =>
    Math.min(Math.max(baseStrokeWidths[selected ? 1 : 0] * zoomScale, MIN_STROKE), MAX_STROKE);
  // with a selection, the other arrows recede so the highlighted one reads first
  const opacityOf = (selected: boolean) => (selected ? 1 : selectedDestinationId ? 0.55 : 0.85);

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
        {/* sized to the view: a straight shadow has no bounding box to size a filter by */}
        <filter
          id="vm-arrow-shadow"
          filterUnits="userSpaceOnUse"
          x={view.left}
          y={view.top}
          width={view.width}
          height={view.height}
        >
          <feGaussianBlur stdDeviation={shadowBlur} />
        </filter>
        <marker
          id="vm-arrowhead"
          viewBox="0 0 10 10"
          refX="8"
          refY="5"
          markerWidth={HEAD_SIZES[0]}
          markerHeight={HEAD_SIZES[0]}
          orient="auto-start-reverse"
        >
          <path d="M0,0 L10,5 L0,10 z" fill="#ef5a2a" stroke="#a83c17" strokeWidth="0.6" />
        </marker>
        <marker
          id="vm-arrowhead-selected"
          viewBox="0 0 10 10"
          refX="8"
          refY="5"
          markerWidth={HEAD_SIZES[1]}
          markerHeight={HEAD_SIZES[1]}
          orient="auto-start-reverse"
        >
          <path d="M0,0 L10,5 L0,10 z" fill="#0c8a83" stroke="#075e59" strokeWidth="0.6" />
        </marker>
      </defs>
      <g filter="url(#vm-arrow-shadow)">
        {paths.map((p) => (
          <path
            key={p.id}
            d={p.shadowD}
            fill="none"
            stroke="#000"
            strokeOpacity={SHADOW_OPACITY * opacityOf(p.selected)}
            strokeWidth={strokeWidthOf(p.selected) + 1}
            strokeLinecap="round"
          />
        ))}
      </g>
      <g>
        {paths.map((p) => {
          const strokeWidth = strokeWidthOf(p.selected);
          return (
            <g key={p.id} opacity={opacityOf(p.selected)}>
              {/* casing: half a pixel of border on each side of the colored stroke */}
              <path
                d={p.d}
                fill="none"
                stroke={p.selected ? '#075e59' : '#a83c17'}
                strokeWidth={strokeWidth + 1}
                strokeLinecap="round"
              />
              <path
                d={p.d}
                fill="none"
                stroke={p.selected ? '#0c8a83' : '#ef5a2a'}
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
