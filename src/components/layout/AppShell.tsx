import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useMapDataStore } from '../../store/mapDataStore';
import type { Destination, LatLng } from '../../types/models';
import { MapView } from '../map/MapView';
import { DestinationDetailPanel } from '../panels/DestinationDetailPanel';
import { DestinationEditForm } from '../panels/DestinationEditForm';
import { MainLocationEditForm } from '../panels/MainLocationEditForm';
import { DestinationListSidebar } from '../controls/DestinationListSidebar';
import { DataImportExportControls } from '../controls/DataImportExportControls';
import { ExportMapImageButton } from '../controls/ExportMapImageButton';
import { RouteDisplayToggle, type RouteDisplayMode } from '../controls/RouteDisplayToggle';
import { fetchRoute } from '../../services/osrmService';
import {
  MapAspectControls,
  type AspectRatioId,
  type MapOrientation,
} from '../controls/MapAspectControls';

type PanelMode = 'view' | 'edit-main' | 'edit-destination' | 'add-destination';

const ASPECT_VALUES: Record<Exclude<AspectRatioId, 'free'>, number> = {
  '16:9': 16 / 9,
  '4:3': 4 / 3,
  '1:1': 1,
};

function frameStyleFor(
  aspect: AspectRatioId,
  orientation: MapOrientation,
  stage: { width: number; height: number },
): React.CSSProperties {
  if (aspect === 'free' || stage.width === 0 || stage.height === 0) {
    return { width: '100%', height: '100%' };
  }
  let ratio = ASPECT_VALUES[aspect];
  if (orientation === 'portrait') ratio = 1 / ratio;
  const width = Math.min(stage.width, stage.height * ratio);
  return { width: Math.floor(width), height: Math.floor(width / ratio) };
}

export function AppShell() {
  const data = useMapDataStore((s) => s.data);
  const isLoaded = useMapDataStore((s) => s.isLoaded);
  const loadError = useMapDataStore((s) => s.loadError);
  const loadInitialData = useMapDataStore((s) => s.loadInitialData);
  const selectedDestinationId = useMapDataStore((s) => s.selectedDestinationId);
  const setSelectedDestination = useMapDataStore((s) => s.setSelectedDestination);
  const setMainLocation = useMapDataStore((s) => s.setMainLocation);
  const addDestination = useMapDataStore((s) => s.addDestination);
  const updateDestination = useMapDataStore((s) => s.updateDestination);
  const removeDestination = useMapDataStore((s) => s.removeDestination);
  const setRouteInfo = useMapDataStore((s) => s.setRouteInfo);

  const [mode, setMode] = useState<PanelMode>('view');
  const [isPicking, setIsPicking] = useState(false);
  const [pickedLocation, setPickedLocation] = useState<LatLng | null>(null);
  const [aspect, setAspect] = useState<AspectRatioId>('free');
  const [orientation, setOrientation] = useState<MapOrientation>('landscape');
  const [displayMode, setDisplayMode] = useState<RouteDisplayMode>('arrows');
  const [stageSize, setStageSize] = useState({ width: 0, height: 0 });
  const [isFullscreen, setIsFullscreen] = useState(false);

  const mapExportRef = useRef<HTMLDivElement>(null);
  const stageRef = useRef<HTMLDivElement>(null);
  const routeFetchesInFlight = useRef(new Set<string>());

  useEffect(() => {
    loadInitialData();
  }, [loadInitialData]);

  useEffect(() => {
    // covers Esc, F11, and our buttons alike
    const onFullscreenChange = () => setIsFullscreen(document.fullscreenElement != null);
    document.addEventListener('fullscreenchange', onFullscreenChange);
    return () => document.removeEventListener('fullscreenchange', onFullscreenChange);
  }, []);

  useEffect(() => {
    // Routes mode needs road geometry for every destination. Older cached
    // routeInfo (from before geometry was stored) has distance/time but no
    // path, so backfill those too. In-flight guard prevents duplicate calls
    // to the public OSRM server while responses are pending.
    if (displayMode !== 'routes' || !data) return;
    for (const dest of data.destinations) {
      if (dest.routeInfo?.geometry || routeFetchesInFlight.current.has(dest.id)) continue;
      routeFetchesInFlight.current.add(dest.id);
      fetchRoute(data.mainLocation.location, dest.location)
        .then((info) => setRouteInfo(dest.id, info))
        .finally(() => routeFetchesInFlight.current.delete(dest.id));
    }
  }, [displayMode, data, setRouteInfo]);

  useLayoutEffect(() => {
    const stage = stageRef.current;
    if (!stage) return;
    const observer = new ResizeObserver(([entry]) => {
      const { width, height } = entry.contentRect;
      setStageSize({ width, height });
    });
    observer.observe(stage);
    return () => observer.disconnect();
    // re-attach when the map area first mounts (after data load)
  }, [isLoaded, data]);

  if (!isLoaded) {
    return <div className="vm-status-screen">Loading vacation map…</div>;
  }

  if (!data) {
    return (
      <div className="vm-status-screen vm-status-error">
        Failed to load vacation data{loadError ? `: ${loadError}` : '.'}
      </div>
    );
  }

  const selectedDestination: Destination | null =
    data.destinations.find((d) => d.id === selectedDestinationId) ?? null;

  const editingDestination: Destination | null = mode === 'edit-destination' ? selectedDestination : null;

  function handleSelectDestination(id: string) {
    setMode('view');
    setSelectedDestination(id);
  }

  function handleMapClickWhilePicking(lat: number, lng: number) {
    if (!isPicking) return;
    setPickedLocation({ lat, lng });
    setIsPicking(false);
  }

  function handleSaveDestination(dest: Omit<Destination, 'id'>) {
    if (mode === 'edit-destination' && editingDestination) {
      updateDestination(editingDestination.id, dest);
      setSelectedDestination(editingDestination.id);
    } else {
      const id = addDestination(dest);
      setSelectedDestination(id);
    }
    setMode('view');
    setPickedLocation(null);
  }

  function handleDeleteDestination() {
    if (!selectedDestination) return;
    if (window.confirm(`Delete "${selectedDestination.name}"?`)) {
      removeDestination(selectedDestination.id);
      setMode('view');
    }
  }

  function enterFullscreen() {
    stageRef.current?.requestFullscreen().catch(() => {
      // browser denied the request (e.g. permissions policy) — nothing to do
    });
  }

  function exitFullscreen() {
    void document.exitFullscreen();
  }

  return (
    <div className="vm-app-shell">
      <header className="vm-toolbar">
        <h1>Vacation Map</h1>
        <div className="vm-toolbar-controls">
          <RouteDisplayToggle mode={displayMode} onChange={setDisplayMode} />
          <button
            type="button"
            className="vm-aspect-btn"
            title="View map full screen"
            aria-label="View map full screen"
            onClick={enterFullscreen}
          >
            <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true">
              <path
                d="M12 3 H17 V8 M17 3 L11.5 8.5 M8 17 H3 V12 M3 17 L8.5 11.5"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </button>
          <MapAspectControls
            aspect={aspect}
            orientation={orientation}
            onAspectChange={setAspect}
            onOrientationChange={setOrientation}
          />
          <ExportMapImageButton targetRef={mapExportRef} />
          <DataImportExportControls />
        </div>
      </header>

      <div className="vm-main-area">
        <div className="vm-map-stage" ref={stageRef}>
          <MapView
            ref={mapExportRef}
            data={data}
            selectedDestinationId={selectedDestinationId}
            onSelectDestination={handleSelectDestination}
            onEditMainLocation={() => setMode('edit-main')}
            onMapClickWhilePicking={handleMapClickWhilePicking}
            frameStyle={frameStyleFor(aspect, orientation, stageSize)}
            displayMode={displayMode}
          />
          {isFullscreen && (
            <button
              type="button"
              className="vm-exit-fullscreen-btn"
              title="Exit full screen"
              aria-label="Exit full screen"
              onClick={exitFullscreen}
            >
              <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true">
                <path
                  d="M16.5 8.5 H11.5 V3.5 M12 8 L17.5 2.5 M3.5 11.5 H8.5 V16.5 M8 12 L2.5 17.5"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
              Exit full screen
            </button>
          )}
        </div>

        <aside className="vm-sidebar">
          {isPicking && (
            <div className="vm-picking-banner">Click anywhere on the map to set the location…</div>
          )}

          <DestinationListSidebar
            destinations={data.destinations}
            selectedDestinationId={selectedDestinationId}
            onSelect={handleSelectDestination}
            onAddNew={() => {
              setSelectedDestination(null);
              setPickedLocation(null);
              setMode('add-destination');
            }}
          />

          <div className="vm-context-panel">
            {mode === 'edit-main' && (
              <MainLocationEditForm
                initial={data.mainLocation}
                pickedLocation={pickedLocation}
                onConsumePickedLocation={() => setPickedLocation(null)}
                onStartPicking={() => setIsPicking(true)}
                onSave={(loc) => {
                  setMainLocation(loc);
                  setMode('view');
                }}
                onCancel={() => setMode('view')}
              />
            )}

            {(mode === 'edit-destination' || mode === 'add-destination') && (
              <DestinationEditForm
                initial={editingDestination}
                pickedLocation={pickedLocation}
                onConsumePickedLocation={() => setPickedLocation(null)}
                onStartPicking={() => setIsPicking(true)}
                onSave={handleSaveDestination}
                onCancel={() => setMode('view')}
              />
            )}

            {mode === 'view' && selectedDestination && (
              <DestinationDetailPanel
                destination={selectedDestination}
                mainLocation={data.mainLocation}
                onEdit={() => setMode('edit-destination')}
                onDelete={handleDeleteDestination}
                onClose={() => setSelectedDestination(null)}
              />
            )}

            {mode === 'view' && !selectedDestination && (
              <div className="vm-empty-panel">Select a destination on the map or in the list.</div>
            )}
          </div>
        </aside>
      </div>
    </div>
  );
}
