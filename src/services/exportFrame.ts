import type { AspectRatioId, MapOrientation } from '../types/display';
import type { TranslateFn } from '../i18n/context';

export const ASPECT_RATIOS: Record<Exclude<AspectRatioId, 'free'>, number> = {
  '16:9': 16 / 9,
  '4:3': 4 / 3,
  '1:1': 1,
};

export interface Rect {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** width / height for the aspect and orientation, or null for "free" */
export function exportRatio(aspect: AspectRatioId, orientation: MapOrientation): number | null {
  if (aspect === 'free') return null;
  const ratio = ASPECT_RATIOS[aspect];
  return orientation === 'portrait' ? 1 / ratio : ratio;
}

// room kept clear around the frame: the map controls sit above it
const FRAME_SIDE_MARGIN = 24;
const FRAME_TOP = 76;
const FRAME_VERTICAL_MARGIN = 120;

/**
 * The export frame drawn over the desktop map stage: the largest rectangle of
 * the chosen ratio that fits the stage minus margins, centred horizontally
 * and placed below the map controls. Null when the aspect is "free".
 */
export function exportFrameRect(
  stage: { width: number; height: number },
  aspect: AspectRatioId,
  orientation: MapOrientation,
): Rect | null {
  const ratio = exportRatio(aspect, orientation);
  if (!ratio || stage.width === 0 || stage.height === 0) return null;
  const availableWidth = stage.width - FRAME_SIDE_MARGIN * 2;
  const availableHeight = stage.height - FRAME_VERTICAL_MARGIN;
  const width = Math.min(availableWidth, availableHeight * ratio);
  const height = width / ratio;
  return {
    x: Math.round((stage.width - width) / 2),
    y: Math.round(FRAME_TOP + (availableHeight - height) / 2),
    width: Math.round(width),
    height: Math.round(height),
  };
}

/** "16:9 · Landscape", "1:1", "Free" — orientation only where it matters. */
export function exportAspectLabel(
  aspect: AspectRatioId,
  orientation: MapOrientation,
  t: TranslateFn,
  withOrientation: boolean,
): string {
  if (aspect === 'free') return t('export.free');
  if (!withOrientation || aspect === '1:1') return aspect;
  return `${aspect} · ${orientation === 'portrait' ? t('export.portrait') : t('export.landscape')}`;
}
