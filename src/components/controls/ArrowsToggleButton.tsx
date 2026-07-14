interface ArrowsToggleButtonProps {
  showArrows: boolean;
  onToggle: (show: boolean) => void;
}

export function ArrowsToggleButton({ showArrows, onToggle }: ArrowsToggleButtonProps) {
  return (
    <button
      type="button"
      className={`vm-aspect-btn${showArrows ? ' vm-aspect-btn-active' : ''}`}
      title={showArrows ? 'Hide arrows' : 'Show arrows'}
      aria-label={showArrows ? 'Hide arrows' : 'Show arrows'}
      aria-pressed={showArrows}
      onClick={() => onToggle(!showArrows)}
    >
      <svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true">
        <path
          d="M2.5 15 Q 8 5 15.5 7.5"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
        />
        <path d="M13 4.5 L17.5 8 L12 9.8 z" fill="currentColor" />
        {!showArrows && (
          <line
            x1="2.5"
            y1="2.5"
            x2="17.5"
            y2="17.5"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
          />
        )}
      </svg>
    </button>
  );
}
