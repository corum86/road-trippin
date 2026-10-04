import { useCallback, useEffect, useMemo, useState } from 'react';
import { translations, type Lang } from './translations';
import { I18nContext, type TranslateFn } from './context';

const STORAGE_KEY = 'vacation-map:lang';

export function I18nProvider({ children }: { children: React.ReactNode }) {
  const [lang, setLang] = useState<Lang>(() =>
    localStorage.getItem(STORAGE_KEY) === 'el' ? 'el' : 'en',
  );

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, lang);
    document.documentElement.lang = lang;
    document.title = translations[lang]['app.title'];
  }, [lang]);

  const t = useCallback<TranslateFn>(
    (key, params) => {
      let text = translations[lang][key];
      if (params) {
        for (const [name, value] of Object.entries(params)) {
          text = text.replaceAll(`{${name}}`, String(value));
        }
      }
      return text;
    },
    [lang],
  );

  const value = useMemo(() => ({ lang, setLang, t }), [lang, t]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}
