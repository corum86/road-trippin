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
import { ArrowsToggleButton } from '../controls/ArrowsToggleButton';
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

  const [mode, setMode] = useState<PanelMode>('view');
  const [isPicking, setIsPicking] = useState(false);
  const [pickedLocation, setPickedLocation] = useState<LatLng | null>(null);
  const [aspect, setAspect] = useState<AspectRatioId>('free');
  const [orientation, setOrientation] = useState<MapOrientation>('landscape');
  const [showArrows, setShowArrows] = useState(true);
  const [stageSize, setStageSize] = useState({ width: 0, height: 0 });

  const mapExportRef = useRef<HTMLDivElement>(null);
  const stageRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    loadInitialData();
  }, [loadInitialData]);

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

  return (
    <div className="vm-app-shell">
      <header className="vm-toolbar">
        <h1>Vacation Map</h1>
        <div className="vm-toolbar-controls">
          <ArrowsToggleButton showArrows={showArrows} onToggle={setShowArrows} />
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
            showArrows={showArrows}
          />
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
