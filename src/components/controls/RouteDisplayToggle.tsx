export type RouteDisplayMode = 'arrows' | 'routes' | 'points';

interface RouteDisplayToggleProps {
  mode: RouteDisplayMode;
  onChange: (mode: RouteDisplayMode) => void;
}

const MODES: Array<{ id: RouteDisplayMode; title: string }> = [
  { id: 'arrows', title: 'Curved arrows' },
  { id: 'routes', title: 'Actual routes' },
  { id: 'points', title: 'Points only' },
];

function ModeIcon({ id }: { id: RouteDisplayMode }) {
  switch (id) {
    case 'arrows':
      return (
        <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true">
          <path
            d="M2.5 15 Q 8 5 15.5 7.5"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
          />
          <path d="M13 4.5 L17.5 8 L12 9.8 z" fill="currentColor" />
        </svg>
      );
    case 'routes':
      return (
        <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true">
          <path
            d="M3 16.5 L6.5 12.5 L9.5 14 L13.5 6.5 L17 4"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
          <circle cx="3" cy="16.5" r="1.6" fill="currentColor" />
          <circle cx="17" cy="4" r="1.6" fill="currentColor" />
        </svg>
      );
    case 'points':
      return (
        <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true">
          <circle cx="5" cy="14" r="2" fill="currentColor" />
          <circle cx="10" cy="6" r="2" fill="currentColor" />
          <circle cx="15.5" cy="12.5" r="2" fill="currentColor" />
        </svg>
      );
  }
}

export function RouteDisplayToggle({ mode, onChange }: RouteDisplayToggleProps) {
  return (
    <div className="vm-aspect-controls" role="group" aria-label="Connection display mode">
      {MODES.map((m) => (
        <button
          key={m.id}
          type="button"
          className={`vm-aspect-btn${mode === m.id ? ' vm-aspect-btn-active' : ''}`}
          title={m.title}
          aria-label={`Show: ${m.title}`}
          aria-pressed={mode === m.id}
          onClick={() => onChange(m.id)}
        >
          <ModeIcon id={m.id} />
        </button>
      ))}
    </div>
  );
}
