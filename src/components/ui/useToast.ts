import { useCallback, useEffect, useRef, useState } from 'react';

const TOAST_MS = 2600;

export type ToastTone = 'success' | 'error';

export interface ToastState {
  id: number;
  message: string;
  tone: ToastTone;
}

/** One transient message at a time; a new one replaces the current. */
export function useToast() {
  const [toast, setToast] = useState<ToastState | null>(null);
  const timer = useRef<number | undefined>(undefined);

  const showToast = useCallback((message: string, tone: ToastTone = 'success') => {
    window.clearTimeout(timer.current);
    setToast({ id: Date.now(), message, tone });
    timer.current = window.setTimeout(() => setToast(null), TOAST_MS);
  }, []);

  useEffect(() => () => window.clearTimeout(timer.current), []);

  return { toast, showToast };
}
