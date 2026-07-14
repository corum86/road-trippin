import { Marker, Popup } from 'react-leaflet';
import type { Destination } from '../../types/models';
import { destinationIcon } from './icons';

interface DestinationMarkerProps {
  destination: Destination;
  selected: boolean;
  onSelect: (id: string) => void;
}

export function DestinationMarker({ destination, selected, onSelect }: DestinationMarkerProps) {
  return (
    <Marker
      position={[destination.location.lat, destination.location.lng]}
      icon={destinationIcon(selected)}
      eventHandlers={{ click: () => onSelect(destination.id) }}
    >
      <Popup>
        <strong>{destination.name}</strong>
      </Popup>
    </Marker>
  );
}
