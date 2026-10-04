import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * A stack of full-screen layers (detail, form, picker, dialog) mirrored into
 * browser history, so the Android back gesture / browser back button pops
 * the top layer instead of leaving the app.
 *
 * Every layer push adds a history entry tagged with its depth. All "back"
 * actions go through history (history.back / history.go), and the popstate
 * handler is the single place that shrinks the stack — so UI buttons and the
 * system back gesture behave identically.
 */
export function useLayerStack<Layer>() {
  const [stack, setStack] = useState<Layer[]>([]);
  // mirror for callbacks that must read the latest stack without re-binding
  const stackRef = useRef(stack);
  stackRef.current = stack;

  useEffect(() => {
    // a reload can leave us on a deep entry with an empty stack; start clean
    if (history.state?.vmDepth) history.replaceState({ vmDepth: 0 }, '');

    const onPopState = (e: PopStateEvent) => {
      const depth = typeof e.state?.vmDepth === 'number' ? e.state.vmDepth : 0;
      setStack((s) => (depth < s.length ? s.slice(0, depth) : s));
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);

  const push = useCallback((layer: Layer) => {
    const next = [...stackRef.current, layer];
    history.pushState({ vmDepth: next.length }, '');
    stackRef.current = next;
    setStack(next);
  }, []);

  /** Pop `count` layers (default 1). */
  const back = useCallback((count = 1) => {
    const current = stackRef.current;
    const n = Math.min(count, current.length);
    if (n === 0) return;
    // only rewind history entries we actually own
    if ((history.state?.vmDepth ?? 0) === current.length) {
      history.go(-n);
    } else {
      setStack(current.slice(0, current.length - n));
    }
  }, []);

  /** Swap the top layer without touching history (e.g. "add" form → new detail). */
  const replaceTop = useCallback((layer: Layer) => {
    const next = [...stackRef.current.slice(0, -1), layer];
    stackRef.current = next;
    setStack(next);
  }, []);

  return { stack, push, back, replaceTop };
}
