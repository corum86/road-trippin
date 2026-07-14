export type AspectRatioId = 'free' | '16:9' | '4:3' | '1:1';
export type MapOrientation = 'landscape' | 'portrait';

interface MapAspectControlsProps {
  aspect: AspectRatioId;
  orientation: MapOrientation;
  onAspectChange: (aspect: AspectRatioId) => void;
  onOrientationChange: (orientation: MapOrientation) => void;
}

const RATIOS: Array<{ id: AspectRatioId; title: string }> = [
  { id: 'free', title: 'Free — fill the window' },
  { id: '16:9', title: '16:9' },
  { id: '4:3', title: '4:3' },
  { id: '1:1', title: 'Square' },
];

function RatioIcon({ id }: { id: AspectRatioId }) {
  switch (id) {
    case 'free':
      // four corners = "fill available space"
      return (
        <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true">
          <path
            d="M2 7 V2 H7 M13 2 H18 V7 M18 13 V18 H13 M7 18 H2 V13"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
          />
        </svg>
      );
    case '16:9':
      return (
        <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true">
          <rect x="1.5" y="5.5" width="17" height="9" rx="1" fill="none" stroke="currentColor" strokeWidth="1.6" />
        </svg>
      );
    case '4:3':
      return (
        <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true">
          <rect x="2.5" y="4" width="15" height="12" rx="1" fill="none" stroke="currentColor" strokeWidth="1.6" />
        </svg>
      );
    case '1:1':
      return (
        <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true">
          <rect x="4" y="4" width="12" height="12" rx="1" fill="none" stroke="currentColor" strokeWidth="1.6" />
        </svg>
      );
  }
}

function OrientationIcon({ orientation }: { orientation: MapOrientation }) {
  return orientation === 'landscape' ? (
    <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true">
      <rect x="2" y="6" width="16" height="8" rx="1" fill="none" stroke="currentColor" strokeWidth="1.6" />
    </svg>
  ) : (
    <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true">
      <rect x="6" y="2" width="8" height="16" rx="1" fill="none" stroke="currentColor" strokeWidth="1.6" />
    </svg>
  );
}

export function MapAspectControls({
  aspect,
  orientation,
  onAspectChange,
  onOrientationChange,
}: MapAspectControlsProps) {
  const orientationLocked = aspect === 'free' || aspect === '1:1';

  return (
    <div className="vm-aspect-controls" role="group" aria-label="Map aspect ratio">
      {RATIOS.map((r) => (
        <button
          key={r.id}
          type="button"
          className={`vm-aspect-btn${aspect === r.id ? ' vm-aspect-btn-active' : ''}`}
          title={r.title}
          aria-label={`Aspect ratio: ${r.title}`}
          aria-pressed={aspect === r.id}
          onClick={() => onAspectChange(r.id)}
        >
          <RatioIcon id={r.id} />
        </button>
      ))}

      <span className="vm-aspect-divider" />

      {(['landscape', 'portrait'] as const).map((o) => (
        <button
          key={o}
          type="button"
          className={`vm-aspect-btn${orientation === o && !orientationLocked ? ' vm-aspect-btn-active' : ''}`}
          title={o === 'landscape' ? 'Landscape' : 'Portrait'}
          aria-label={`Orientation: ${o}`}
          aria-pressed={orientation === o && !orientationLocked}
          disabled={orientationLocked}
          onClick={() => onOrientationChange(o)}
        >
          <OrientationIcon orientation={o} />
        </button>
      ))}
    </div>
  );
}
