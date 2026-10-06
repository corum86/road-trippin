import { useI18n } from '../../i18n/context';
import { ConfirmDialog } from './ConfirmDialog';

interface ConfirmDeleteDialogProps {
  name: string;
  onCancel: () => void;
  onConfirm: () => void;
  className?: string;
}

export function ConfirmDeleteDialog({ name, onCancel, onConfirm, className }: ConfirmDeleteDialogProps) {
  const { t } = useI18n();
  return (
    <ConfirmDialog
      className={className}
      title={t('shell.confirmDelete', { name })}
      confirmLabel={t('detail.delete')}
      onCancel={onCancel}
      onConfirm={onConfirm}
    />
  );
}
