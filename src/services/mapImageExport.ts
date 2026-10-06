import { toCanvas, toPng } from 'html-to-image';
import type { Rect } from './exportFrame';

// Chromium caps canvases at ~16384px per side; stay below it.
const MAX_CANVAS_SIDE = 16000;

// UI that sits inside the map container but doesn't belong in the image
const EXPORT_HIDDEN_CLASSES = ['leaflet-control-zoom', 'vm-town-label', 'vm-town-labels-status'];

const OSM_ATTRIBUTION = '© OpenStreetMap contributors';

/**
 * Render a map container to a PNG data URL at (up to) the given pixel ratio,
 * optionally cropped to `crop` (in the node's CSS pixels).
 */
export async function renderMapToPng(node: HTMLElement, pixelRatio: number, crop?: Rect): Promise<string> {
  const ratio = Math.min(pixelRatio, MAX_CANVAS_SIDE / Math.max(node.offsetWidth, node.offsetHeight));
  const options = {
    cacheBust: true,
    pixelRatio: ratio,
    // keep OSM attribution (required by tile usage policy) but drop UI controls
    filter: (el: Node) =>
      !(el instanceof HTMLElement && EXPORT_HIDDEN_CLASSES.some((name) => el.classList.contains(name))),
  };
  if (!crop) return toPng(node, options);

  const full = await toCanvas(node, options);
  const out = document.createElement('canvas');
  out.width = Math.round(crop.width * ratio);
  out.height = Math.round(crop.height * ratio);
  const ctx = out.getContext('2d');
  if (!ctx) throw new Error('Canvas 2D context unavailable');
  ctx.drawImage(full, crop.x * ratio, crop.y * ratio, out.width, out.height, 0, 0, out.width, out.height);
  // the map's own attribution control usually falls outside the crop
  drawAttribution(ctx, ratio);
  return out.toDataURL('image/png');
}

function drawAttribution(ctx: CanvasRenderingContext2D, ratio: number) {
  const fontSize = 11 * ratio;
  const padX = 6 * ratio;
  const padY = 3 * ratio;
  ctx.font = `${fontSize}px Poppins, system-ui, sans-serif`;
  const width = ctx.measureText(OSM_ATTRIBUTION).width + padX * 2;
  const height = fontSize + padY * 2;
  const x = ctx.canvas.width - width;
  const y = ctx.canvas.height - height;
  ctx.fillStyle = 'rgba(255, 255, 255, 0.8)';
  ctx.fillRect(x, y, width, height);
  ctx.fillStyle = '#333';
  ctx.textBaseline = 'middle';
  ctx.fillText(OSM_ATTRIBUTION, x + padX, y + height / 2);
}

export function downloadDataUrl(dataUrl: string, filename: string): void {
  const link = document.createElement('a');
  link.download = filename;
  link.href = dataUrl;
  link.click();
}

/**
 * Hand the image to the OS share sheet where the browser supports sharing
 * files (Android Chrome: "Save to Photos", messaging apps, …), otherwise
 * download it. Resolves false if the user dismissed the share sheet.
 */
export async function saveOrSharePng(dataUrl: string, filename: string): Promise<boolean> {
  const blob = await (await fetch(dataUrl)).blob();
  const file = new File([blob], filename, { type: 'image/png' });
  if (navigator.canShare?.({ files: [file] })) {
    try {
      await navigator.share({ files: [file] });
      return true;
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') return false;
      // NotAllowedError: the tap's user activation expired while tiles
      // loaded — fall back to a plain download
    }
  }
  downloadDataUrl(dataUrl, filename);
  return true;
}
