import { useEffect, useState } from 'react';
import { useMapDataStore } from '../../store/mapDataStore';
import type { LatLng } from '../../types/models';
import { useI18n } from '../../i18n/context';
import type { TranslationKey } from '../../i18n/translations';
import { useRouteGeometryBackfill } from '../../hooks/useRouteGeometryBackfill';
import type { ExportOptions, RouteDisplayMode } from '../../types/display';
import { exportAspectLabel } from '../../services/exportFrame';
import { Icon } from '../ui/Icon';
import { useLayerStack } from './useLayerStack';
import { MapScreen } from './MapScreen';
import { PlacesScreen } from '../screens/PlacesScreen';
import { TripScreen } from '../screens/TripScreen';
import { SettingsScreen } from '../screens/SettingsScreen';
import { DestinationDetailScreen } from '../screens/DestinationDetailScreen';
import { LocationFormScreen } from '../screens/LocationFormScreen';
import { PickOnMapScreen } from './PickOnMapScreen';
import { ConfirmDeleteDialog } from '../ui/ConfirmDeleteDialog';
import { OffscreenMapExport } from './OffscreenMapExport';
import { Toast } from '../ui/Toast';
import { useToast } from '../ui/useToast';
import '../screens/screens.css';
import './MobileAppShell.css';

type Tab = 'map' | 'places' | 'trip' | 'settings';

type Layer =
  | { kind: 'detail'; id: string }
  | { kind: 'destination-form'; id: string | null }
  | { kind: 'home-form' }
  | { kind: 'pick' }
  | { kind: 'confirm-delete'; id: string };

const TABS: Array<{ id: Tab; icon: string; labelKey: TranslationKey }> = [
  { id: 'map', icon: 'map', labelKey: 'tabs.map' },
  { id: 'places', icon: 'pin_drop', labelKey: 'tabs.places' },
  { id: 'trip', icon: 'luggage', labelKey: 'tabs.trip' },
  { id: 'settings', icon: 'settings', labelKey: 'tabs.settings' },
];

export function MobileAppShell() {
  const { t } = useI18n();
  const data = useMapDataStore((s) => s.data);
  const isLoaded = useMapDataStore((s) => s.isLoaded);
  const loadError = useMapDataStore((s) => s.loadError);
  const loadInitialData = useMapDataStore((s) => s.loadInitialData);
  const setSelectedDestination = useMapDataStore((s) => s.setSelectedDestination);
  const addDestination = useMapDataStore((s) => s.addDestination);
  const updateDestination = useMapDataStore((s) => s.updateDestination);
  const removeDestination = useMapDataStore((s) => s.removeDestination);
  const setMainLocation = useMapDataStore((s) => s.setMainLocation);

  const [tab, setTab] = useState<Tab>('map');
  const [displayMode, setDisplayMode] = useState<RouteDisplayMode>('arrows');
  const [pickedLocation, setPickedLocation] = useState<LatLng | null>(null);
  const [exportOptions, setExportOptions] = useState<ExportOptions>({
    aspect: '16:9',
    orientation: 'landscape',
    quality: 2,
  });
  // the options of the export in flight, captured when it started
  const [exportJob, setExportJob] = useState<ExportOptions | null>(null);
  const { toast, showToast } = useToast();
  const { stack, push, back, replaceTop } = useLayerStack<Layer>();

  useEffect(() => {
    loadInitialData();
  }, [loadInitialData]);

  useRouteGeometryBackfill(displayMode === 'routes', data);

  if (!isLoaded) {
    return <div className="vm-status-screen">{t('app.loading')}</div>;
  }

  if (!data) {
    return (
      <div className="vm-status-screen vm-status-error">
        {t('app.loadFailed')}
        {loadError ? `: ${loadError}` : '.'}
      </div>
    );
  }

  const openDetail = (id: string) => {
    setSelectedDestination(id);
    push({ kind: 'detail', id });
  };

  const openAdd = () => {
    setPickedLocation(null);
    push({ kind: 'destination-form', id: null });
  };

  function renderLayer(layer: Layer) {
    if (!data) return null;
    switch (layer.kind) {
      case 'detail': {
        const destination = data.destinations.find((d) => d.id === layer.id);
        // briefly absent between a delete and the history pop that closes it
        if (!destination) return null;
        return (
          <DestinationDetailScreen
            destination={destination}
            home={data.mainLocation}
            variant="mobile"
            onBack={() => back()}
            onEdit={() => {
              setPickedLocation(null);
              push({ kind: 'destination-form', id: layer.id });
            }}
            onDelete={() => push({ kind: 'confirm-delete', id: layer.id })}
            onToast={showToast}
          />
        );
      }
      case 'destination-form': {
        const initial = layer.id ? (data.destinations.find((d) => d.id === layer.id) ?? null) : null;
        return (
          <LocationFormScreen
            kind="destination"
            variant="mobile"
            initial={initial}
            pickedLocation={pickedLocation}
            onConsumePickedLocation={() => setPickedLocation(null)}
            onStartPicking={() => push({ kind: 'pick' })}
            onClose={() => back()}
            onSave={(draft) => {
              if (initial) {
                updateDestination(initial.id, draft);
                back();
              } else {
                const id = addDestination(draft);
                setSelectedDestination(id);
                replaceTop({ kind: 'detail', id });
              }
              showToast(t('toast.saved'));
            }}
          />
        );
      }
      case 'home-form':
        return (
          <LocationFormScreen
            kind="home"
            variant="mobile"
            initial={data.mainLocation}
            pickedLocation={pickedLocation}
            onConsumePickedLocation={() => setPickedLocation(null)}
            onStartPicking={() => push({ kind: 'pick' })}
            onClose={() => back()}
            onSave={(home) => {
              setMainLocation(home);
              back();
              showToast(t('toast.saved'));
            }}
          />
        );
      case 'pick':
        return (
          <PickOnMapScreen
            data={data}
            displayMode={displayMode}
            onPick={(lat, lng) => {
              setPickedLocation({ lat, lng });
              back();
            }}
            onCancel={() => back()}
          />
        );
      case 'confirm-delete': {
        const destination = data.destinations.find((d) => d.id === layer.id);
        if (!destination) return null;
        return (
          <ConfirmDeleteDialog
            name={destination.name}
            onCancel={() => back()}
            onConfirm={() => {
              removeDestination(destination.id);
              // close the dialog and the detail screen underneath it
              back(2);
              showToast(t('toast.deleted'));
            }}
          />
        );
      }
    }
  }

  const onMainScreen = stack.length === 0;

  return (
    <div className="vm-mobile-shell">
      <div className="vm-mobile-content">
        {tab === 'map' && (
          <MapScreen
            data={data}
            displayMode={displayMode}
            onDisplayModeChange={setDisplayMode}
            onOpenDetail={openDetail}
            onAdd={openAdd}
          />
        )}
        {tab === 'places' && <PlacesScreen data={data} variant="mobile" onOpenDetail={openDetail} onAdd={openAdd} />}
        {tab === 'trip' && <TripScreen data={data} onOpenDetail={openDetail} />}
        {tab === 'settings' && (
          <SettingsScreen
            data={data}
            variant="mobile"
            exportOptions={exportOptions}
            onExportOptionsChange={setExportOptions}
            exporting={!!exportJob}
            onExport={() => setExportJob(exportOptions)}
            onEditHome={() => {
              setPickedLocation(null);
              push({ kind: 'home-form' });
            }}
          />
        )}
        {exportJob && (
          <OffscreenMapExport
            data={data}
            displayMode={displayMode}
            options={exportJob}
            onDone={(result) => {
              setExportJob(null);
              if (!result.ok) {
                showToast(result.error || t('export.failed'), 'error');
              } else if (result.saved) {
                const aspect = exportAspectLabel(exportJob.aspect, exportJob.orientation, t, false);
                showToast(t('export.saved', { a: aspect, q: `${exportJob.quality}x` }));
              }
            }}
          />
        )}

        {stack.map((layer, i) => (
          <div key={`${i}-${layer.kind}`} className={`vm-mobile-layer-host vm-mobile-layer-host-${layer.kind}`}>
            {renderLayer(layer)}
          </div>
        ))}

        {toast && <Toast key={toast.id} toast={toast} className={onMainScreen ? undefined : 'vm-mobile-toast-raised'} />}
      </div>

      {onMainScreen && (
        <nav className="vm-mobile-nav" aria-label={t('app.title')}>
          {TABS.map((item) => {
            const active = tab === item.id;
            return (
              <button
                key={item.id}
                type="button"
                className={`vm-mobile-nav-item${active ? ' vm-mobile-nav-item-active' : ''}`}
                aria-current={active ? 'page' : undefined}
                onClick={() => setTab(item.id)}
              >
                <span className="vm-mobile-nav-indicator">
                  <Icon name={item.icon} size={24} filled={active} />
                </span>
                <span className="vm-mobile-nav-label">{t(item.labelKey)}</span>
              </button>
            );
          })}
        </nav>
      )}
    </div>
  );
}
