import { createContext, useContext } from 'react';
import type { Lang, TranslationKey } from './translations';

export type TranslateFn = (key: TranslationKey, params?: Record<string, string | number>) => string;

export interface I18nContextValue {
  lang: Lang;
  setLang: (lang: Lang) => void;
  t: TranslateFn;
}

export const I18nContext = createContext<I18nContextValue | null>(null);

export function useI18n(): I18nContextValue {
  const ctx = useContext(I18nContext);
  if (!ctx) throw new Error('useI18n must be used within an I18nProvider');
  return ctx;
}
