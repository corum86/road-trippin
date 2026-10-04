import { Marker, Popup } from 'react-leaflet';
import type { MainLocation } from '../../types/models';
import type { MapDensity } from '../../types/display';
import { mainLocationIcon, pinLabelGap } from './icons';
import { PinLabel } from './PinLabel';
import { shortPlaceName } from '../../services/placeNames';
import { useI18n } from '../../i18n/context';

interface MainLocationMarkerProps {
  mainLocation: MainLocation;
  onEdit: () => void;
  showLabel?: boolean;
  density?: MapDensity;
}

export function MainLocationMarker({ mainLocation, onEdit, showLabel, density = 'compact' }: MainLocationMarkerProps) {
  const { t } = useI18n();
  return (
    <Marker
      position={[mainLocation.location.lat, mainLocation.location.lng]}
      icon={mainLocationIcon(density)}
      eventHandlers={{ click: onEdit }}
    >
      {showLabel ? (
        <PinLabel
          position={mainLocation.location}
          text={shortPlaceName(mainLocation.name)}
          gap={pinLabelGap('main', density)}
          density={density}
        />
      ) : (
        <Popup>
          <strong>{mainLocation.name}</strong>
          <div>{t('marker.mainLocation')}</div>
        </Popup>
      )}
    </Marker>
  );
}
