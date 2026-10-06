import { useI18n } from '../../i18n/context';

interface ConfirmDialogProps {
  title: string;
  /** what confirming does, when the title alone doesn't say */
  body?: string;
  confirmLabel: string;
  onCancel: () => void;
  onConfirm: () => void;
  className?: string;
}

/** Asks before something that can't be undone. Closes on backdrop click. */
export function ConfirmDialog({ title, body, confirmLabel, onCancel, onConfirm, className }: ConfirmDialogProps) {
  const { t } = useI18n();
  return (
    <div className={`vm-dialog-backdrop${className ? ` ${className}` : ''}`} onClick={onCancel}>
      <div
        className="vm-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="vm-dialog-title"
        aria-describedby={body ? 'vm-dialog-body' : undefined}
        onClick={(e) => e.stopPropagation()}
      >
        <h2 id="vm-dialog-title" className="vm-dialog-title">
          {title}
        </h2>
        {body && (
          <p id="vm-dialog-body" className="vm-dialog-body">
            {body}
          </p>
        )}
        <div className="vm-dialog-actions">
          <button type="button" className="vm-text-btn" onClick={onCancel}>
            {t('form.cancel')}
          </button>
          <button type="button" className="vm-btn vm-btn-danger vm-btn-dialog" onClick={onConfirm} autoFocus>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
