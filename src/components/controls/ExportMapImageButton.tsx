import { useState } from 'react';
import { toPng } from 'html-to-image';

interface ExportMapImageButtonProps {
  targetRef: React.RefObject<HTMLDivElement | null>;
}

const QUALITY_OPTIONS = [
  { pixelRatio: 2, label: '2x — standard' },
  { pixelRatio: 3, label: '3x — high' },
  { pixelRatio: 4, label: '4x — HD' },
];

// Chromium caps canvases at ~16384px per side; stay below it.
const MAX_CANVAS_SIDE = 16000;

export function ExportMapImageButton({ targetRef }: ExportMapImageButtonProps) {
  const [isExporting, setIsExporting] = useState(false);
  const [pixelRatio, setPixelRatio] = useState(2);
  const [error, setError] = useState<string | null>(null);

  async function handleExport() {
    const node = targetRef.current;
    if (!node) return;
    setIsExporting(true);
    setError(null);
    try {
      const ratio = Math.min(
        pixelRatio,
        MAX_CANVAS_SIDE / Math.max(node.offsetWidth, node.offsetHeight),
      );
      const dataUrl = await toPng(node, {
        cacheBust: true,
        pixelRatio: ratio,
        // keep OSM attribution (required by tile usage policy) but drop UI controls
        filter: (el) =>
          !(el instanceof HTMLElement && el.classList.contains('leaflet-control-zoom')),
      });
      const link = document.createElement('a');
      link.download = `vacation-map-${Date.now()}.png`;
      link.href = dataUrl;
      link.click();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to export map image.');
    } finally {
      setIsExporting(false);
    }
  }

  return (
    <div className="vm-export-image-control">
      <select
        value={pixelRatio}
        onChange={(e) => setPixelRatio(Number(e.target.value))}
        aria-label="Export image quality"
        title="Export image quality"
      >
        {QUALITY_OPTIONS.map((q) => (
          <option key={q.pixelRatio} value={q.pixelRatio}>
            {q.label}
          </option>
        ))}
      </select>
      <button type="button" onClick={handleExport} disabled={isExporting}>
        {isExporting ? 'Exporting…' : 'Export map as image'}
      </button>
      {error && <span className="vm-import-error">{error}</span>}
    </div>
  );
}
