import { useI18n } from '../../i18n/context';

interface ConfirmDeleteDialogProps {
  name: string;
  onCancel: () => void;
  onConfirm: () => void;
  className?: string;
}

export function ConfirmDeleteDialog({ name, onCancel, onConfirm, className }: ConfirmDeleteDialogProps) {
  const { t } = useI18n();
  return (
    <div className={`vm-dialog-backdrop${className ? ` ${className}` : ''}`} onClick={onCancel}>
      <div
        className="vm-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="vm-dialog-title"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 id="vm-dialog-title" className="vm-dialog-title">
          {t('shell.confirmDelete', { name })}
        </h2>
        <div className="vm-dialog-actions">
          <button type="button" className="vm-text-btn" onClick={onCancel}>
            {t('form.cancel')}
          </button>
          <button type="button" className="vm-btn vm-btn-danger vm-btn-dialog" onClick={onConfirm} autoFocus>
            {t('detail.delete')}
          </button>
        </div>
      </div>
    </div>
  );
}
