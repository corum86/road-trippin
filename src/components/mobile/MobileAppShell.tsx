import { useEffect, useRef, useState } from 'react';
import { useMapDataStore } from '../../store/mapDataStore';
import { useCloudSyncStore } from '../../store/cloudSync';
import type { PickedLocation } from '../../types/models';
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
import { TripDatesPicker } from '../screens/TripDatesPicker';
import { AddToDaySheet } from '../screens/AddToDaySheet';
import { unscheduledDestinations } from '../../services/tripPlan';
import { SwapSheet } from '../screens/SwapSheet';
import { ReplanSheet } from '../screens/ReplanSheet';
import { TripWizard } from '../wizard/TripWizard';
import { ConfirmDeleteDialog } from '../ui/ConfirmDeleteDialog';
import { ConfirmDialog } from '../ui/ConfirmDialog';
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
  | { kind: 'confirm-delete'; id: string }
  | { kind: 'confirm-clear-trip' }
  | { kind: 'confirm-reset' }
  | { kind: 'trip-dates' }
  | { kind: 'add-to-day'; day: number }
  | { kind: 'swap'; day: number; index: number }
  | { kind: 'replan' }
  | { kind: 'planner' };

// sheets (and the dialogs a tab opens itself) sit over the current tab, bottom
// nav included; everything else is a full-screen layer that replaces the nav
const SHEET_LAYERS: ReadonlyArray<Layer['kind']> = [
  'trip-dates',
  'add-to-day',
  'swap',
  'replan',
  'confirm-clear-trip',
  'confirm-reset',
];

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
  const setTripDates = useMapDataStore((s) => s.setTripDates);
  const applyPlannedTrip = useMapDataStore((s) => s.applyPlannedTrip);
  const clearTrip = useMapDataStore((s) => s.clearTrip);
  const clearAllData = useMapDataStore((s) => s.clearAllData);
  const cloudSync = useCloudSyncStore((s) => s.status !== 'unavailable');

  const [tab, setTab] = useState<Tab>('map');
  const [displayMode, setDisplayMode] = useState<RouteDisplayMode>('arrows');
  const [pickedLocation, setPickedLocation] = useState<PickedLocation | null>(null);
  const [exportOptions, setExportOptions] = useState<ExportOptions>({
    aspect: '16:9',
    orientation: 'landscape',
    quality: 2,
  });
  // the options of the export in flight, captured when it started
  const [exportJob, setExportJob] = useState<ExportOptions | null>(null);
  const { toast, showToast, dismissToast } = useToast();
  // the trip planner steps back through its own steps on the back gesture
  const plannerBack = useRef<(() => boolean) | null>(null);
  const { stack, push, back, replaceTop } = useLayerStack<Layer>(
    (top) => top.kind === 'planner' && (plannerBack.current?.() ?? false),
  );

  useEffect(() => {
    loadInitialData();
  }, [loadInitialData]);

  // first launch with no places: start with the trip planner
  const plannerAutoOpened = useRef(false);
  useEffect(() => {
    if (!data || plannerAutoOpened.current) return;
    plannerAutoOpened.current = true;
    if (data.destinations.length === 0 && data.mainLocation) push({ kind: 'planner' });
  }, [data, push]);

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

  const openPlanner = () => {
    // the planner plans around the home base: ask for that first
    if (!data.mainLocation) {
      showToast(t('trip.needsHome'));
      setPickedLocation(null);
      push({ kind: 'home-form' });
      return;
    }
    push({ kind: 'planner' });
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
            trip={data.trip}
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
            onPick={(lat, lng, name) => {
              setPickedLocation({ lat, lng, name });
              back();
            }}
            onCancel={() => back()}
          />
        );
      case 'trip-dates':
        return (
          <TripDatesPicker
            variant="mobile"
            trip={data.trip}
            onClose={() => back()}
            onSave={(start, end) => {
              setTripDates(start, end);
              back();
              showToast(t('trip.datesSaved'));
            }}
          />
        );
      case 'add-to-day':
        return <AddToDaySheet variant="mobile" data={data} dayIndex={layer.day} onClose={() => back()} />;
      case 'swap':
        return (
          <SwapSheet
            variant="mobile"
            data={data}
            dayIndex={layer.day}
            index={layer.index}
            onClose={() => back()}
            onToast={showToast}
          />
        );
      case 'replan':
        return <ReplanSheet variant="mobile" data={data} onClose={() => back()} onToast={showToast} />;
      case 'planner':
        // gone if another device cleared the map meanwhile
        if (!data.mainLocation) return null;
        return (
          <TripWizard
            variant="mobile"
            data={data}
            home={data.mainLocation}
            backHandlerRef={plannerBack}
            onClose={() => back()}
            onChangeHome={() => {
              setPickedLocation(null);
              push({ kind: 'home-form' });
            }}
            onFinish={(planned) => {
              applyPlannedTrip(planned);
              back();
              setTab('trip');
              showToast(t('trip.wizSaved', { n: planned.places.length }));
            }}
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
      case 'confirm-clear-trip':
        return (
          <ConfirmDialog
            title={t('trip.clearConfirmTitle')}
            body={t('trip.clearConfirmBody')}
            confirmLabel={t('trip.clear')}
            onCancel={() => back()}
            onConfirm={() => {
              clearTrip();
              back();
              showToast(t('trip.cleared'));
            }}
          />
        );
      case 'confirm-reset':
        return (
          <ConfirmDialog
            title={t('data.confirmResetTitle')}
            body={[t('data.confirmReset'), cloudSync && t('data.confirmResetSynced')].filter(Boolean).join(' ')}
            confirmLabel={t('data.confirmResetAction')}
            onCancel={() => back()}
            onConfirm={() => {
              clearAllData();
              back();
              showToast(t('data.cleared'));
            }}
          />
        );
    }
  }

  const onMainScreen = stack.every((layer) => SHEET_LAYERS.includes(layer.kind));
  // the Trip screen's not-scheduled tray sits where the toast would
  const trayVisible =
    tab === 'trip' && onMainScreen && unscheduledDestinations(data.trip, data.destinations).length > 0;
  const toastClass = !onMainScreen ? 'vm-mobile-toast-raised' : trayVisible ? 'vm-mobile-toast-above-tray' : undefined;

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
        {tab === 'trip' && (
          <TripScreen
            data={data}
            onOpenDetail={openDetail}
            onOpenDates={() => push({ kind: 'trip-dates' })}
            onAddToDay={(day) => push({ kind: 'add-to-day', day })}
            onSwap={(day, index) => push({ kind: 'swap', day, index })}
            onReplan={() => push({ kind: 'replan' })}
            onOpenPlanner={openPlanner}
            onClearTrip={() => push({ kind: 'confirm-clear-trip' })}
          />
        )}
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
            onReset={() => push({ kind: 'confirm-reset' })}
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

        {toast && <Toast key={toast.id} toast={toast} onDismiss={dismissToast} className={toastClass} />}
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
