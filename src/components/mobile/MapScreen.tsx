import { useMemo } from 'react';
import { useMapDataStore } from '../../store/mapDataStore';
import type { VacationMapData } from '../../types/models';
import { useI18n } from '../../i18n/context';
import type { TranslationKey } from '../../i18n/translations';
import { MapView } from '../map/MapView';
import { FitToPoints, KeepInView, type MapPadding } from '../map/MapViewport';
import type { RouteDisplayMode } from '../../types/display';
import { Icon } from '../ui/Icon';
import { PhotoThumb } from '../ui/PhotoThumb';
import { allPhotos, routeText, statusLabel } from '../screens/destinationHelpers';
import { mapPoints } from '../../services/geo';

interface MapScreenProps {
  data: VacationMapData;
  displayMode: RouteDisplayMode;
  onDisplayModeChange: (mode: RouteDisplayMode) => void;
  onOpenDetail: (id: string) => void;
  onAdd: () => void;
}

const DISPLAY_MODES: Array<{ id: RouteDisplayMode; icon: string; titleKey: TranslationKey }> = [
  { id: 'arrows', icon: 'conversion_path', titleKey: 'routes.arrows' },
  { id: 'routes', icon: 'route', titleKey: 'routes.actual' },
  { id: 'points', icon: 'scatter_plot', titleKey: 'routes.points' },
];

// keep pins clear of the floating top bar and the bottom carousel / card
const FIT_PADDING: MapPadding = { top: 100, right: 40, bottom: 150, left: 40 };
const SELECTED_PADDING: MapPadding = { ...FIT_PADDING, bottom: 200 };

export function MapScreen({ data, displayMode, onDisplayModeChange, onOpenDetail, onAdd }: MapScreenProps) {
  const { t, lang, setLang } = useI18n();
  const selectedId = useMapDataStore((s) => s.selectedDestinationId);
  const setSelected = useMapDataStore((s) => s.setSelectedDestination);

  const selected = data.destinations.find((d) => d.id === selectedId) ?? null;

  const points = useMemo(
    () => mapPoints(data.mainLocation, data.destinations),
    [data.mainLocation, data.destinations],
  );
  const pointsKey = points.map((p) => `${p.lat},${p.lng}`).join('|');

  const modeIndex = DISPLAY_MODES.findIndex((m) => m.id === displayMode);
  const mode = DISPLAY_MODES[modeIndex];
  const nextMode = DISPLAY_MODES[(modeIndex + 1) % DISPLAY_MODES.length];

  return (
    <div className={`vm-screen vm-mobile-map-screen${selected ? ' vm-mobile-map-screen-selected' : ''}`}>
      <MapView
        data={data}
        selectedDestinationId={selectedId}
        onSelectDestination={setSelected}
        onEditMainLocation={() => setSelected(null)}
        onMapClick={() => setSelected(null)}
        frameStyle={{ width: '100%', height: '100%' }}
        displayMode={displayMode}
        showLabels
        zoomControl={false}
      >
        <FitToPoints points={points} padding={FIT_PADDING} fitKey={pointsKey} />
        <KeepInView point={selected?.location ?? null} padding={SELECTED_PADDING} />
      </MapView>

      <div className="vm-mobile-topbar">
        <span className="vm-mobile-topbar-title">{t('app.title')}</span>
        <button
          type="button"
          className="vm-mobile-round-btn"
          title={t(mode.titleKey)}
          aria-label={t('routes.show', { title: t(nextMode.titleKey) })}
          onClick={() => onDisplayModeChange(nextMode.id)}
        >
          <Icon name={mode.icon} size={21} />
        </button>
        <button
          type="button"
          className="vm-mobile-lang-btn"
          aria-label={t('map.toggleLanguage')}
          onClick={() => setLang(lang === 'en' ? 'el' : 'en')}
        >
          {lang === 'en' ? 'EN' : 'ΕΛ'}
        </button>
      </div>

      {selected ? (
        <div className="vm-mobile-selected-card">
          <div className="vm-mobile-selected-row">
            <PhotoThumb photo={allPhotos(selected)[0]} className="vm-mobile-selected-photo" />
            <div className="vm-mobile-selected-text">
              <div className="vm-mobile-selected-name">{selected.name}</div>
              <div className="vm-mobile-selected-route">{routeText(selected, data.mainLocation, t)}</div>
              <div className="vm-mobile-selected-status">{statusLabel(selected, t)}</div>
            </div>
            <button
              type="button"
              className="vm-circle-btn vm-mobile-selected-close"
              aria-label={t('detail.close')}
              onClick={() => setSelected(null)}
            >
              <Icon name="close" size={22} />
            </button>
          </div>
          <button type="button" className="vm-btn vm-btn-primary" onClick={() => onOpenDetail(selected.id)}>
            {t('detail.details')}
          </button>
        </div>
      ) : (
        <div className="vm-mobile-carousel">
          {data.destinations.map((dest) => (
            <button
              key={dest.id}
              type="button"
              className="vm-mobile-carousel-card"
              onClick={() => setSelected(dest.id)}
            >
              <span className="vm-mobile-carousel-status">
                <span className={`vm-status-dot vm-status-dot-${dest.status}`} />
                {statusLabel(dest, t)}
              </span>
              <span className="vm-mobile-carousel-name">{dest.name}</span>
              <span className="vm-mobile-carousel-route">{routeText(dest, data.mainLocation, t)}</span>
            </button>
          ))}
          <button
            type="button"
            className="vm-mobile-carousel-add"
            aria-label={t('form.addDestination')}
            onClick={onAdd}
          >
            <Icon name="add" size={28} />
          </button>
        </div>
      )}
    </div>
  );
}
