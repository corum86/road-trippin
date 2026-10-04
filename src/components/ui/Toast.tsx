import { Icon } from './Icon';
import type { ToastState } from './useToast';

export function Toast({ toast, className }: { toast: ToastState; className?: string }) {
  return (
    <div className={`vm-toast${className ? ` ${className}` : ''}`} role="status" aria-live="polite">
      <Icon
        name={toast.tone === 'error' ? 'error' : 'check_circle'}
        size={20}
        className={toast.tone === 'error' ? 'vm-toast-icon-error' : 'vm-toast-icon'}
      />
      {toast.message}
    </div>
  );
}
