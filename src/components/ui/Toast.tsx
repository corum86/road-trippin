import { Icon } from './Icon';
import type { ToastState } from './useToast';

interface ToastProps {
  toast: ToastState;
  /** called after the toast's action (e.g. Undo) has run */
  onDismiss: () => void;
  className?: string;
}

export function Toast({ toast, onDismiss, className }: ToastProps) {
  const { action } = toast;
  return (
    <div className={`vm-toast${className ? ` ${className}` : ''}`} role="status" aria-live="polite">
      <Icon
        name={toast.tone === 'error' ? 'error' : 'check_circle'}
        size={20}
        className={toast.tone === 'error' ? 'vm-toast-icon-error' : 'vm-toast-icon'}
      />
      <span className="vm-toast-message">{toast.message}</span>
      {action && (
        <button
          type="button"
          className="vm-toast-action"
          onClick={() => {
            action.onAction();
            onDismiss();
          }}
        >
          {action.label}
        </button>
      )}
    </div>
  );
}
