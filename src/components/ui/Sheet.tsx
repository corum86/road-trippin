import { useEffect } from 'react';

interface SheetProps {
  /**
   * `bottom`: slides up from the bottom edge over a scrim (mobile).
   * `dialog`: centred card over a scrim (desktop).
   * `popover`: anchored card with an invisible click-catcher (desktop).
   */
  layout: 'bottom' | 'dialog' | 'popover';
  onClose: () => void;
  /** id of the element that titles the sheet */
  labelledBy: string;
  className?: string;
  children: React.ReactNode;
}

/** Modal surface for short tasks. Closes on scrim click and Esc. */
export function Sheet({ layout, onClose, labelledBy, className, children }: SheetProps) {
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      // capture phase + stop: the sheet is the innermost thing Esc should close
      e.stopPropagation();
      onClose();
    };
    window.addEventListener('keydown', onKeyDown, true);
    return () => window.removeEventListener('keydown', onKeyDown, true);
  }, [onClose]);

  return (
    <div className={`vm-sheet-scrim vm-sheet-scrim-${layout}`} onClick={onClose}>
      <div
        className={`vm-sheet vm-sheet-${layout}${className ? ` ${className}` : ''}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby={labelledBy}
        onClick={(e) => e.stopPropagation()}
      >
        {layout === 'bottom' && <div className="vm-sheet-handle" aria-hidden="true" />}
        {children}
      </div>
    </div>
  );
}
