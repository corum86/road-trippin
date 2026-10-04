import { useI18n } from '../../i18n/context';
import type { TranslationKey } from '../../i18n/translations';
import type { RouteDisplayMode } from '../../types/display';
import { Icon } from '../ui/Icon';

const MODES: Array<{ id: RouteDisplayMode; icon: string; labelKey: TranslationKey; titleKey: TranslationKey }> = [
  { id: 'arrows', icon: 'conversion_path', labelKey: 'routes.arrowsShort', titleKey: 'routes.arrows' },
  { id: 'routes', icon: 'route', labelKey: 'routes.routesShort', titleKey: 'routes.actual' },
  { id: 'points', icon: 'scatter_plot', labelKey: 'routes.pointsShort', titleKey: 'routes.points' },
];

interface MapControlsProps {
  displayMode: RouteDisplayMode;
  onDisplayModeChange: (mode: RouteDisplayMode) => void;
  exporting: boolean;
  onExport: () => void;
}

/** Floating top-left controls on the desktop map stage. */
export function MapControls({ displayMode, onDisplayModeChange, exporting, onExport }: MapControlsProps) {
  const { t } = useI18n();
  return (
    <div className="vm-desktop-map-controls">
      <div className="vm-desktop-mode-switch" role="group" aria-label={t('routes.group')}>
        {MODES.map((m) => (
          <button
            key={m.id}
            type="button"
            className={`vm-desktop-mode-btn${displayMode === m.id ? ' vm-desktop-mode-btn-active' : ''}`}
            title={t(m.titleKey)}
            aria-pressed={displayMode === m.id}
            onClick={() => onDisplayModeChange(m.id)}
          >
            <Icon name={m.icon} size={19} />
            {t(m.labelKey)}
          </button>
        ))}
      </div>
      <button type="button" className="vm-desktop-export-btn" onClick={onExport} disabled={exporting}>
        <Icon name="download" size={20} className="vm-text-coral" />
        {exporting ? t('export.exporting') : t('export.imageShort')}
      </button>
    </div>
  );
}
