/** How home → destination connections are drawn on the map. */
export type RouteDisplayMode = 'arrows' | 'routes' | 'points';

export type AspectRatioId = 'free' | '16:9' | '4:3' | '1:1';
export type MapOrientation = 'landscape' | 'portrait';

export interface ExportOptions {
  aspect: AspectRatioId;
  orientation: MapOrientation;
  /** html-to-image pixel ratio: 2, 3 or 4 */
  quality: number;
}

/** Pin, label and arrow sizing: compact on phones, comfortable on desktop. */
export type MapDensity = 'compact' | 'comfortable';
