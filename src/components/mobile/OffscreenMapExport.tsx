import { useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { VacationMapData } from '../../types/models';
import type { ExportOptions, RouteDisplayMode } from '../../types/display';
import { exportRatio } from '../../services/exportFrame';
import { renderMapToPng, saveOrSharePng } from '../../services/mapImageExport';
import { MapView } from '../map/MapView';
import { FitToPoints, WhenTilesLoaded } from '../map/MapViewport';

// CSS width of the offscreen map for fixed ratios; × quality gives the PNG
// width (2x → 1920px wide)
const EXPORT_BASE_WIDTH = 960;
const EXPORT_PADDING = { top: 72, right: 40, bottom: 40, left: 40 };
// nav bar height: "Free" exports what the Map tab shows
const NAV_BAR_HEIGHT = 80;

export type ExportResult = { ok: true; saved: boolean } | { ok: false; error: string };

interface OffscreenMapExportProps {
  data: VacationMapData;
  displayMode: RouteDisplayMode;
  options: ExportOptions;
  onDone: (result: ExportResult) => void;
}

/**
 * Phones have no map on screen while Settings is open, so the export renders
 * one at the chosen size outside the viewport, waits for its tiles, captures
 * it, and hands the PNG to the share sheet (or downloads it).
 */
export function OffscreenMapExport({ data, displayMode, options, onDone }: OffscreenMapExportProps) {
  const ref = useRef<HTMLDivElement>(null);
  const started = useRef(false);

  // fixed at mount: the job's size must not change while tiles load
  const [size] = useState(() => {
    const ratio = exportRatio(options.aspect, options.orientation);
    return ratio
      ? { width: EXPORT_BASE_WIDTH, height: Math.round(EXPORT_BASE_WIDTH / ratio) }
      : { width: window.innerWidth, height: window.innerHeight - NAV_BAR_HEIGHT };
  });

  const points = useMemo(
    () => [data.mainLocation.location, ...data.destinations.map((d) => d.location)],
    [data.mainLocation, data.destinations],
  );

  async function capture() {
    const node = ref.current;
    // StrictMode can mount the map twice; capture once
    if (!node || started.current) return;
    started.current = true;
    try {
      const dataUrl = await renderMapToPng(node, options.quality);
      const saved = await saveOrSharePng(dataUrl, `vacation-map-${Date.now()}.png`);
      onDone({ ok: true, saved });
    } catch (err) {
      onDone({ ok: false, error: err instanceof Error ? err.message : '' });
    }
  }

  return createPortal(
    <div className="vm-mobile-export-stage" aria-hidden="true">
      <MapView
        ref={ref}
        data={data}
        selectedDestinationId={null}
        onSelectDestination={() => {}}
        onEditMainLocation={() => {}}
        frameStyle={size}
        displayMode={displayMode}
        showLabels
        zoomControl={false}
      >
        <FitToPoints points={points} padding={EXPORT_PADDING} fitKey="export" />
        <WhenTilesLoaded onLoaded={capture} />
      </MapView>
    </div>,
    document.body,
  );
}
