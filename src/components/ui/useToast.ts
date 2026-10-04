import { useCallback, useEffect, useRef, useState } from 'react';

const TOAST_MS = 2600;
// long enough to read the message and reach for the action
const TOAST_WITH_ACTION_MS = 5000;

export type ToastTone = 'success' | 'error';

export interface ToastAction {
  label: string;
  onAction: () => void;
}

export interface ToastState {
  id: number;
  message: string;
  tone: ToastTone;
  action?: ToastAction;
}

export type ShowToast = (message: string, tone?: ToastTone, action?: ToastAction) => void;

/** One transient message at a time; a new one replaces the current. */
export function useToast() {
  const [toast, setToast] = useState<ToastState | null>(null);
  const timer = useRef<number | undefined>(undefined);

  const showToast = useCallback<ShowToast>((message, tone = 'success', action) => {
    window.clearTimeout(timer.current);
    setToast({ id: Date.now(), message, tone, action });
    timer.current = window.setTimeout(() => setToast(null), action ? TOAST_WITH_ACTION_MS : TOAST_MS);
  }, []);

  const dismissToast = useCallback(() => {
    window.clearTimeout(timer.current);
    setToast(null);
  }, []);

  useEffect(() => () => window.clearTimeout(timer.current), []);

  return { toast, showToast, dismissToast };
}
