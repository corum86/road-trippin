import { Marker, Popup } from 'react-leaflet';
import type { MainLocation } from '../../types/models';
import { mainLocationIcon } from './icons';

interface MainLocationMarkerProps {
  mainLocation: MainLocation;
  onEdit: () => void;
}

export function MainLocationMarker({ mainLocation, onEdit }: MainLocationMarkerProps) {
  return (
    <Marker
      position={[mainLocation.location.lat, mainLocation.location.lng]}
      icon={mainLocationIcon}
      eventHandlers={{ click: onEdit }}
    >
      <Popup>
        <strong>{mainLocation.name}</strong>
        <div>Main location</div>
      </Popup>
    </Marker>
  );
}
