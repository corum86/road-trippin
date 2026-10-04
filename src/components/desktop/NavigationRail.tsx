import { useI18n } from '../../i18n/context';
import type { TranslationKey } from '../../i18n/translations';
import { Icon } from '../ui/Icon';

export type DesktopTab = 'map' | 'places' | 'trip' | 'settings';

const TABS: Array<{ id: DesktopTab; icon: string; labelKey: TranslationKey }> = [
  { id: 'map', icon: 'map', labelKey: 'tabs.map' },
  { id: 'places', icon: 'pin_drop', labelKey: 'tabs.places' },
  { id: 'trip', icon: 'luggage', labelKey: 'tabs.trip' },
  { id: 'settings', icon: 'settings', labelKey: 'tabs.settings' },
];

interface NavigationRailProps {
  tab: DesktopTab;
  onSelect: (tab: DesktopTab) => void;
}

export function NavigationRail({ tab, onSelect }: NavigationRailProps) {
  const { t, lang, setLang } = useI18n();
  return (
    <nav className="vm-desktop-rail" aria-label={t('app.title')}>
      <div className="vm-desktop-rail-mark" aria-hidden="true">
        ★
      </div>
      {TABS.map((item) => {
        const active = tab === item.id;
        return (
          <button
            key={item.id}
            type="button"
            className={`vm-desktop-rail-item${active ? ' vm-desktop-rail-item-active' : ''}`}
            aria-current={active ? 'page' : undefined}
            onClick={() => onSelect(item.id)}
          >
            <span className="vm-desktop-rail-indicator">
              <Icon name={item.icon} size={24} filled={active} />
            </span>
            <span className="vm-desktop-rail-label">{t(item.labelKey)}</span>
          </button>
        );
      })}
      <div className="vm-desktop-rail-spacer" />
      <button
        type="button"
        className="vm-desktop-rail-lang"
        title={t('settings.language')}
        aria-label={t('map.toggleLanguage')}
        onClick={() => setLang(lang === 'en' ? 'el' : 'en')}
      >
        {lang === 'en' ? 'EN' : 'ΕΛ'}
      </button>
    </nav>
  );
}
