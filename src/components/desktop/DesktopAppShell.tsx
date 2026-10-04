import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useMapDataStore } from '../../store/mapDataStore';
import type { LatLng } from '../../types/models';
import type { ExportOptions, RouteDisplayMode } from '../../types/display';
import { useI18n } from '../../i18n/context';
import { useRouteGeometryBackfill } from '../../hooks/useRouteGeometryBackfill';
import { exportAspectLabel, exportFrameRect } from '../../services/exportFrame';
import { downloadDataUrl, renderMapToPng } from '../../services/mapImageExport';
import { MapView } from '../map/MapView';
import { FitToPoints, KeepInView, type MapPadding } from '../map/MapViewport';
import { AiSearchTrigger } from '../controls/AiSearchTrigger';
import { PlacesScreen } from '../screens/PlacesScreen';
import { TripScreen } from '../screens/TripScreen';
import { SettingsScreen } from '../screens/SettingsScreen';
import { DestinationDetailScreen } from '../screens/DestinationDetailScreen';
import { LocationFormScreen } from '../screens/LocationFormScreen';
import { ConfirmDeleteDialog } from '../ui/ConfirmDeleteDialog';
import { Toast } from '../ui/Toast';
import { useToast } from '../ui/useToast';
import { Icon } from '../ui/Icon';
import { NavigationRail, type DesktopTab } from './NavigationRail';
import { MapControls } from './MapControls';
import '../screens/screens.css';
import './DesktopAppShell.css';

type PanelTab = Exclude<DesktopTab, 'map'>;

type Floating = { kind: 'detail' } | { kind: 'destination-form'; id: string | null } | { kind: 'home-form' };

// Fit padding keeps pins clear of the map controls (top), attribution
// (bottom), and the floating Detail/Form panel (right) when it is open.
const FIT_PADDING: MapPadding = { top: 120, right: 140, bottom: 70, left: 90 };
const FLOATING_PANEL_CLEARANCE = 470;
// breathing room between the export frame and the outermost coordinates;
// pins and their labels draw above/beside the point, so the top needs more
const FRAME_FIT_MARGIN = { top: 72, side: 40, bottom: 28 };
// the side panel animates its width over 200ms; refit once it has settled
const PANEL_SETTLE_MS = 220;

const canUseAi = !!import.meta.env.VITE_GEMINI_API_KEY;

/** Something with its own Esc handling (photo lightbox, AI stepper) is open. */
function hasOwnOverlayOpen(): boolean {
  return document.querySelector('.vm-lightbox-backdrop, .vm-stepper-backdrop') !== null;
}

/**
 * Desktop layout (≥1024px): navigation rail, side panel (Places / Trip /
 * Settings, hidden on the Map tab) and the map stage, with Detail and the
 * edit forms floating over the right side of the map.
 */
export function DesktopAppShell() {
  const { t } = useI18n();
  const data = useMapDataStore((s) => s.data);
  const isLoaded = useMapDataStore((s) => s.isLoaded);
  const loadError = useMapDataStore((s) => s.loadError);
  const loadInitialData = useMapDataStore((s) => s.loadInitialData);
  const selectedId = useMapDataStore((s) => s.selectedDestinationId);
  const setSelected = useMapDataStore((s) => s.setSelectedDestination);
  const setMainLocation = useMapDataStore((s) => s.setMainLocation);
  const addDestination = useMapDataStore((s) => s.addDestination);
  const updateDestination = useMapDataStore((s) => s.updateDestination);
  const removeDestination = useMapDataStore((s) => s.removeDestination);

  const [tab, setTab] = useState<DesktopTab>('places');
  // keeps showing while the panel collapses for the Map tab
  const [panelTab, setPanelTab] = useState<PanelTab>('places');
  const [floating, setFloating] = useState<Floating | null>(null);
  const [picking, setPicking] = useState(false);
  const [pickedLocation, setPickedLocation] = useState<LatLng | null>(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [displayMode, setDisplayMode] = useState<RouteDisplayMode>('arrows');
  const [exportOptions, setExportOptions] = useState<ExportOptions>({
    aspect: 'free',
    orientation: 'landscape',
    quality: 2,
  });
  const [exporting, setExporting] = useState(false);
  const [stageSize, setStageSize] = useState({ width: 0, height: 0 });
  const { toast, showToast } = useToast();

  const stageRef = useRef<HTMLDivElement>(null);
  const mapExportRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    loadInitialData();
  }, [loadInitialData]);

  useRouteGeometryBackfill(displayMode === 'routes', data);

  const hasData = data !== null;
  useLayoutEffect(() => {
    const stage = stageRef.current;
    if (!stage) return;
    const observer = new ResizeObserver(([entry]) => {
      const { width, height } = entry.contentRect;
      setStageSize({ width, height });
    });
    observer.observe(stage);
    return () => observer.disconnect();
    // re-attach when the stage first mounts (after data load)
  }, [isLoaded, hasData]);

  const points = useMemo(
    () => (data ? [data.mainLocation.location, ...data.destinations.map((d) => d.location)] : []),
    [data],
  );

  const selected = data?.destinations.find((d) => d.id === selectedId) ?? null;
  const detailOpen = floating?.kind === 'detail' && !!selected;

  function closeDetail() {
    setFloating(null);
    setSelected(null);
  }

  function closeForm() {
    setPicking(false);
    // cancelling an edit returns to that destination's Detail
    setFloating(floating?.kind === 'destination-form' && floating.id ? { kind: 'detail' } : null);
  }

  // Esc closes the innermost thing: dialog → picking → floating panel
  const escRef = useRef<() => void>(() => {});
  escRef.current = () => {
    if (confirmDeleteId) setConfirmDeleteId(null);
    else if (picking) setPicking(false);
    else if (floating?.kind === 'detail') closeDetail();
    else if (floating) closeForm();
  };
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !hasOwnOverlayOpen()) escRef.current();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

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

  const panelVisible = tab !== 'map';
  const floatingOpen = detailOpen || floating?.kind === 'destination-form' || floating?.kind === 'home-form';
  const frame = exportFrameRect(stageSize, exportOptions.aspect, exportOptions.orientation);

  // with an export frame showing, fit the trip inside it so the export
  // captures every pin; otherwise keep clear of the overlaid UI
  const fitPadding: MapPadding = frame
    ? {
        top: frame.y + FRAME_FIT_MARGIN.top,
        left: frame.x + FRAME_FIT_MARGIN.side,
        right: stageSize.width - frame.x - frame.width + FRAME_FIT_MARGIN.side,
        bottom: stageSize.height - frame.y - frame.height + FRAME_FIT_MARGIN.bottom,
      }
    : { ...FIT_PADDING, right: floatingOpen ? FLOATING_PANEL_CLEARANCE : FIT_PADDING.right };
  const fitKey = [
    points.map((p) => `${p.lat},${p.lng}`).join('|'),
    panelVisible,
    floatingOpen,
    frame ? `${frame.x},${frame.y},${frame.width},${frame.height}` : 'free',
  ].join('/');

  function selectTab(next: DesktopTab) {
    setTab(next);
    if (next !== 'map') setPanelTab(next);
  }

  function openDetail(id: string) {
    // a click elsewhere must not throw away unsaved form input
    if (floating && floating.kind !== 'detail') return;
    setSelected(id);
    setFloating({ kind: 'detail' });
  }

  function openAdd() {
    setSelected(null);
    setPickedLocation(null);
    setPicking(false);
    setFloating({ kind: 'destination-form', id: null });
  }

  function openHomeForm() {
    setSelected(null);
    setPickedLocation(null);
    setPicking(false);
    setFloating({ kind: 'home-form' });
  }

  function handleMapClick(lat: number, lng: number) {
    if (picking) {
      setPickedLocation({ lat, lng });
      setPicking(false);
    } else if (floating?.kind === 'detail') {
      closeDetail();
    } else if (!floating) {
      setSelected(null);
    }
  }

  async function handleExport() {
    const node = mapExportRef.current;
    if (!node || exporting) return;
    setExporting(true);
    try {
      const dataUrl = await renderMapToPng(node, exportOptions.quality, frame ?? undefined);
      downloadDataUrl(dataUrl, `vacation-map-${Date.now()}.png`);
      const aspect = frame
        ? exportAspectLabel(exportOptions.aspect, exportOptions.orientation, t, false)
        : `${Math.round(stageSize.width)}×${Math.round(stageSize.height)}`;
      showToast(t('export.downloaded', { a: aspect, q: `${exportOptions.quality}x` }));
    } catch (err) {
      showToast(err instanceof Error ? err.message : t('export.failed'), 'error');
    } finally {
      setExporting(false);
    }
  }

  function renderPanel() {
    if (!data) return null;
    switch (panelTab) {
      case 'places':
        return (
          <PlacesScreen
            data={data}
            variant="desktop"
            selectedId={detailOpen ? selectedId : null}
            onOpenDetail={openDetail}
            onAdd={openAdd}
            headerActions={canUseAi ? <AiSearchTrigger onError={(m) => showToast(m, 'error')} /> : null}
          />
        );
      case 'trip':
        return <TripScreen data={data} selectedId={detailOpen ? selectedId : null} onOpenDetail={openDetail} />;
      case 'settings':
        return (
          <SettingsScreen
            data={data}
            variant="desktop"
            exportOptions={exportOptions}
            onExportOptionsChange={setExportOptions}
            exporting={exporting}
            onExport={handleExport}
            onEditHome={openHomeForm}
          />
        );
    }
  }

  function renderFloating() {
    if (!data || !floating) return null;
    if (floating.kind === 'detail') {
      if (!selected) return null;
      return (
        <DestinationDetailScreen
          key={selected.id}
          destination={selected}
          home={data.mainLocation}
          variant="desktop"
          onBack={closeDetail}
          onEdit={() => {
            setPickedLocation(null);
            setFloating({ kind: 'destination-form', id: selected.id });
          }}
          onDelete={() => setConfirmDeleteId(selected.id)}
          onToast={showToast}
        />
      );
    }
    const formProps = {
      variant: 'desktop' as const,
      picking,
      pickedLocation,
      onConsumePickedLocation: () => setPickedLocation(null),
      onStartPicking: () => setPicking((p) => !p),
      onClose: closeForm,
    };
    if (floating.kind === 'home-form') {
      return (
        <LocationFormScreen
          key="home"
          kind="home"
          initial={data.mainLocation}
          {...formProps}
          onSave={(home) => {
            setMainLocation(home);
            setPicking(false);
            setFloating(null);
            showToast(t('toast.saved'));
          }}
        />
      );
    }
    const initial = floating.id ? (data.destinations.find((d) => d.id === floating.id) ?? null) : null;
    return (
      <LocationFormScreen
        key={floating.id ?? 'new'}
        kind="destination"
        initial={initial}
        {...formProps}
        onSave={(draft) => {
          if (initial) {
            updateDestination(initial.id, draft);
          } else {
            setSelected(addDestination(draft));
          }
          setPicking(false);
          setFloating({ kind: 'detail' });
          showToast(t('toast.saved'));
        }}
      />
    );
  }

  const confirmTarget = confirmDeleteId ? data.destinations.find((d) => d.id === confirmDeleteId) : undefined;

  return (
    <div className="vm-desktop-shell">
      <NavigationRail tab={tab} onSelect={selectTab} />

      <aside className={`vm-desktop-panel${panelVisible ? '' : ' vm-desktop-panel-collapsed'}`} inert={!panelVisible}>
        <div className="vm-desktop-panel-inner">{renderPanel()}</div>
      </aside>

      <main className={`vm-desktop-stage${picking ? ' vm-desktop-stage-picking' : ''}`} ref={stageRef}>
        <MapView
          ref={mapExportRef}
          data={data}
          selectedDestinationId={detailOpen ? selectedId : null}
          onSelectDestination={openDetail}
          onEditMainLocation={openHomeForm}
          onMapClick={handleMapClick}
          frameStyle={{ width: '100%', height: '100%' }}
          displayMode={displayMode}
          showLabels
          density="comfortable"
          zoomControl={false}
          attributionPosition="bottomleft"
        >
          <FitToPoints points={points} padding={fitPadding} fitKey={fitKey} delayMs={PANEL_SETTLE_MS} />
          <KeepInView point={detailOpen && selected ? selected.location : null} padding={fitPadding} />
        </MapView>

        {frame && (
          <div
            className="vm-desktop-export-frame"
            style={{ left: frame.x, top: frame.y, width: frame.width, height: frame.height }}
            aria-hidden="true"
          >
            <span className="vm-desktop-export-frame-label">
              {t('export.frame', {
                a: exportAspectLabel(exportOptions.aspect, exportOptions.orientation, t, true),
              })}
            </span>
          </div>
        )}

        <MapControls
          displayMode={displayMode}
          onDisplayModeChange={setDisplayMode}
          exporting={exporting}
          onExport={handleExport}
        />

        {picking && (
          <div className="vm-desktop-pick-banner" role="status">
            <Icon name="ads_click" size={22} />
            <span>{t('shell.pickingBanner')}</span>
            <button type="button" className="vm-desktop-pick-cancel" onClick={() => setPicking(false)}>
              {t('form.cancel')}
            </button>
          </div>
        )}

        {renderFloating()}

        {toast && <Toast key={toast.id} toast={toast} className="vm-desktop-toast" />}
      </main>

      {confirmTarget && (
        <ConfirmDeleteDialog
          className="vm-desktop-dialog-backdrop"
          name={confirmTarget.name}
          onCancel={() => setConfirmDeleteId(null)}
          onConfirm={() => {
            removeDestination(confirmTarget.id);
            setConfirmDeleteId(null);
            setFloating(null);
            showToast(t('toast.deleted'));
          }}
        />
      )}
    </div>
  );
}
