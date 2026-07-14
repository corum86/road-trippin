import L from 'leaflet';

export const mainLocationIcon = L.divIcon({
  className: 'vm-marker vm-marker-main',
  html: `<div class="vm-marker-pin vm-marker-pin-main"><span>★</span></div>`,
  iconSize: [34, 34],
  iconAnchor: [17, 34],
  popupAnchor: [0, -30],
});

export function destinationIcon(selected: boolean): L.DivIcon {
  return L.divIcon({
    className: 'vm-marker vm-marker-destination',
    html: `<div class="vm-marker-pin vm-marker-pin-destination${selected ? ' vm-marker-pin-selected' : ''}"></div>`,
    iconSize: [26, 26],
    iconAnchor: [13, 26],
    popupAnchor: [0, -22],
  });
}
