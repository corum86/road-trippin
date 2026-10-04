import { Marker, Popup } from 'react-leaflet';
import type { Destination } from '../../types/models';
import type { MapDensity } from '../../types/display';
import { destinationIcon, pinLabelGap } from './icons';
import { PinLabel } from './PinLabel';

interface DestinationMarkerProps {
  destination: Destination;
  selected: boolean;
  onSelect: (id: string) => void;
  showLabel?: boolean;
  density?: MapDensity;
}

export function DestinationMarker({
  destination,
  selected,
  onSelect,
  showLabel,
  density = 'compact',
}: DestinationMarkerProps) {
  return (
    <Marker
      position={[destination.location.lat, destination.location.lng]}
      icon={destinationIcon(selected, destination.status === 'visited', density)}
      eventHandlers={{ click: () => onSelect(destination.id) }}
    >
      {showLabel ? (
        <PinLabel
          position={destination.location}
          text={destination.name}
          gap={pinLabelGap(selected ? 'selected' : 'destination', density)}
          density={density}
        />
      ) : (
        <Popup>
          <strong>{destination.name}</strong>
        </Popup>
      )}
    </Marker>
  );
}
