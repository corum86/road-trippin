import L from 'leaflet';
import type { MapDensity } from '../../types/display';

// Pins are a rounded square of side `z` rotated -45° into a teardrop. Its tip
// sits ~1.2z below the box top, which is where the icon anchors to the
// coordinate; the round head is centred 0.7z above that.
function pinGeometry(z: number) {
  return {
    iconSize: [z, z] as [number, number],
    iconAnchor: [z / 2, Math.round(z * 1.2)] as [number, number],
    popupAnchor: [0, -Math.round(z * 1.1)] as [number, number],
    // name labels (permanent tooltips) centre on the head vertically; the
    // horizontal gap is applied per side by PinLabel (pinLabelGap)
    tooltipAnchor: [0, -Math.round(z * 0.7)] as [number, number],
  };
}

type PinKind = 'main' | 'destination' | 'selected';

const PIN_SIZES: Record<MapDensity, Record<PinKind, number>> = {
  compact: { main: 34, destination: 24, selected: 30 },
  comfortable: { main: 38, destination: 26, selected: 32 },
};

/** Distance from a pin's centre line to its name label's near edge. */
export function pinLabelGap(kind: PinKind, density: MapDensity = 'compact'): number {
  return PIN_SIZES[density][kind] / 2 + 4;
}

const CHECK_SVG =
  '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5" fill="none" stroke="#fff" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round"/></svg>';

// cached so re-renders hand react-leaflet the same instance (a new icon
// object makes it swap the marker's DOM element)
const iconCache = new Map<string, L.DivIcon>();

function cached(key: string, create: () => L.DivIcon): L.DivIcon {
  let icon = iconCache.get(key);
  if (!icon) {
    icon = create();
    iconCache.set(key, icon);
  }
  return icon;
}

export function mainLocationIcon(density: MapDensity = 'compact'): L.DivIcon {
  const z = PIN_SIZES[density].main;
  return cached(`main-${density}`, () =>
    L.divIcon({
      className: 'vm-marker vm-marker-main',
      html: `<div class="vm-marker-pin vm-marker-pin-main" style="width:${z}px;height:${z}px"><span>★</span></div>`,
      ...pinGeometry(z),
    }),
  );
}

export function destinationIcon(selected: boolean, visited = false, density: MapDensity = 'compact'): L.DivIcon {
  const z = PIN_SIZES[density][selected ? 'selected' : 'destination'];
  return cached(`dest-${selected}-${visited}-${density}`, () =>
    L.divIcon({
      className: 'vm-marker vm-marker-destination',
      html: `<div class="vm-marker-pin vm-marker-pin-destination${selected ? ' vm-marker-pin-selected' : ''}" style="width:${z}px;height:${z}px">${
        visited ? `<span class="vm-marker-check">${CHECK_SVG}</span>` : ''
      }</div>`,
      ...pinGeometry(z),
    }),
  );
}
