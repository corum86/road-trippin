import { useEffect, useState } from 'react';
import { Tooltip, useMap, useMapEvents } from 'react-leaflet';
import type { LatLng } from '../../types/models';
import type { MapDensity } from '../../types/display';

interface PinLabelProps {
  position: LatLng;
  text: string;
  /** horizontal distance from the pin's centre to the label (see pinLabelGap) */
  gap: number;
  density?: MapDensity;
}

// pins in the right part of the view get their label on the left so it never
// runs off the edge: the right 45% on phones, 40% on desktop
const FLIP_THRESHOLD: Record<MapDensity, number> = { compact: 0.55, comfortable: 0.6 };

/** Always-visible name pill beside a marker (render as a Marker child). */
export function PinLabel({ position, text, gap, density = 'compact' }: PinLabelProps) {
  const map = useMap();

  const sideFor = (): 'left' | 'right' =>
    map.latLngToContainerPoint([position.lat, position.lng]).x > map.getSize().x * FLIP_THRESHOLD[density]
      ? 'left'
      : 'right';

  const [side, setSide] = useState(sideFor);
  const recompute = () => setSide(sideFor());

  useMapEvents({ moveend: recompute, zoomend: recompute, resize: recompute });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(recompute, [position.lat, position.lng]);

  // Leaflet only mirrors offsets for direction 'auto', so flip the gap here.
  // react-leaflet does not update direction/offset in place; remount instead.
  return (
    <Tooltip
      key={`${side}-${gap}`}
      permanent
      direction={side}
      offset={[side === 'right' ? gap : -gap, 0]}
      className={`vm-pin-label${density === 'comfortable' ? ' vm-pin-label-lg' : ''}`}
    >
      {text}
    </Tooltip>
  );
}
