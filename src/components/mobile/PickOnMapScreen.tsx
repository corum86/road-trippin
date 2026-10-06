import { useMemo } from 'react';
import type { VacationMapData } from '../../types/models';
import { useI18n } from '../../i18n/context';
import { MapView } from '../map/MapView';
import { FitToPoints } from '../map/MapViewport';
import type { RouteDisplayMode } from '../../types/display';
import { Icon } from '../ui/Icon';
import { mapPoints } from '../../services/geo';

interface PickOnMapScreenProps {
  data: VacationMapData;
  displayMode: RouteDisplayMode;
  /** `name` is set when a town's name was tapped rather than a bare map point */
  onPick: (lat: number, lng: number, name?: string) => void;
  onCancel: () => void;
}

const PICK_PADDING = { top: 110, right: 40, bottom: 60, left: 40 };

export function PickOnMapScreen({ data, displayMode, onPick, onCancel }: PickOnMapScreenProps) {
  const { t } = useI18n();
  const points = useMemo(
    () => mapPoints(data.mainLocation, data.destinations),
    [data.mainLocation, data.destinations],
  );

  return (
    <div className="vm-layer vm-mobile-pick">
      <MapView
        data={data}
        selectedDestinationId={null}
        onSelectDestination={() => {}}
        onEditMainLocation={() => {}}
        onMapClick={(lat, lng) => onPick(lat, lng)}
        onSelectPlace={(place) => onPick(place.location.lat, place.location.lng, place.name)}
        frameStyle={{ width: '100%', height: '100%' }}
        displayMode={displayMode}
        showLabels
        zoomControl={false}
      >
        <FitToPoints points={points} padding={PICK_PADDING} fitKey="pick" />
      </MapView>
      <div className="vm-mobile-pick-banner" role="status">
        <Icon name="touch_app" size={22} />
        <span className="vm-mobile-pick-text">{t('shell.pickingBannerTap')}</span>
        <button type="button" className="vm-circle-btn" aria-label={t('form.cancel')} onClick={onCancel}>
          <Icon name="close" size={22} />
        </button>
      </div>
    </div>
  );
}
