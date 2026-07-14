import { useState } from 'react';
import { toPng } from 'html-to-image';

interface ExportMapImageButtonProps {
  targetRef: React.RefObject<HTMLDivElement | null>;
}

export function ExportMapImageButton({ targetRef }: ExportMapImageButtonProps) {
  const [isExporting, setIsExporting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleExport() {
    const node = targetRef.current;
    if (!node) return;
    setIsExporting(true);
    setError(null);
    try {
      const dataUrl = await toPng(node, {
        cacheBust: true,
        pixelRatio: 2,
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
      <button type="button" onClick={handleExport} disabled={isExporting}>
        {isExporting ? 'Exporting…' : 'Export map as image'}
      </button>
      {error && <span className="vm-import-error">{error}</span>}
    </div>
  );
}
