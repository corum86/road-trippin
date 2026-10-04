import type { VacationMapData } from '../../types/models';
import type { AspectRatioId, ExportOptions, MapOrientation } from '../../types/display';
import { useI18n } from '../../i18n/context';
import type { Lang, TranslationKey } from '../../i18n/translations';
import { exportAspectLabel } from '../../services/exportFrame';
import { DataImportExportControls } from '../controls/DataImportExportControls';
import { Icon } from '../ui/Icon';
import { SectionLabel } from '../ui/SectionLabel';

interface SettingsScreenProps {
  data: VacationMapData;
  variant: 'mobile' | 'desktop';
  exportOptions: ExportOptions;
  onExportOptionsChange: (options: ExportOptions) => void;
  exporting: boolean;
  onExport: () => void;
  onEditHome: () => void;
}

const ASPECTS: AspectRatioId[] = ['free', '16:9', '4:3', '1:1'];
const ORIENTATIONS: MapOrientation[] = ['landscape', 'portrait'];
const QUALITIES = [2, 3, 4];
const LANGUAGES: Array<{ id: Lang; labelKey: TranslationKey }> = [
  { id: 'en', labelKey: 'lang.english' },
  { id: 'el', labelKey: 'lang.greek' },
];

export function SettingsScreen({
  data,
  variant,
  exportOptions,
  onExportOptionsChange,
  exporting,
  onExport,
  onEditHome,
}: SettingsScreenProps) {
  const { t, lang, setLang } = useI18n();
  const home = data.mainLocation;
  const isDesktop = variant === 'desktop';
  const { aspect, orientation, quality } = exportOptions;
  // orientation only matters for non-square fixed ratios
  const orientationLocked = aspect === 'free' || aspect === '1:1';
  const update = (patch: Partial<ExportOptions>) => onExportOptionsChange({ ...exportOptions, ...patch });

  function optionButton(key: string, label: string, active: boolean, onClick: () => void, disabled = false) {
    return (
      <button
        key={key}
        type="button"
        className={`vm-option${active ? ' vm-option-active' : ''}`}
        aria-pressed={active}
        disabled={disabled}
        onClick={onClick}
      >
        {label}
      </button>
    );
  }

  return (
    <div className="vm-screen">
      <div className="vm-scroll vm-settings">
        <h1 className="vm-screen-title vm-settings-title">{t('tabs.settings')}</h1>

        <section className="vm-settings-section">
          <SectionLabel>{t('form.homeBase')}</SectionLabel>
          <button type="button" className="vm-card vm-home-card" onClick={onEditHome}>
            <span className="vm-home-badge">★</span>
            <span className="vm-home-text">
              <span className="vm-home-name">{home.name}</span>
              <span className="vm-mono vm-meta">
                {home.location.lat.toFixed(5)}, {home.location.lng.toFixed(5)}
              </span>
            </span>
            <Icon name="edit" size={20} className="vm-text-muted" />
          </button>
        </section>

        <section className="vm-settings-section">
          <SectionLabel>{t('settings.language')}</SectionLabel>
          <div className="vm-segmented" role="group" aria-label={t('settings.language')}>
            {LANGUAGES.map((l) => (
              <button
                key={l.id}
                type="button"
                className={`vm-segment${lang === l.id ? ' vm-segment-active vm-text-teal' : ''}`}
                aria-pressed={lang === l.id}
                onClick={() => setLang(l.id)}
              >
                {t(l.labelKey)}
              </button>
            ))}
          </div>
        </section>

        <section className="vm-settings-section">
          <SectionLabel>{t('export.button')}</SectionLabel>
          <div className="vm-card vm-export-card">
            <div className="vm-option-group">
              <span className="vm-option-label">{t('export.aspect')}</span>
              <div className="vm-options" role="group" aria-label={t('export.aspect')}>
                {ASPECTS.map((a) =>
                  optionButton(a, exportAspectLabel(a, orientation, t, false), aspect === a, () => update({ aspect: a })),
                )}
              </div>
            </div>
            {isDesktop && (
              <div className={`vm-option-group${orientationLocked ? ' vm-option-group-locked' : ''}`}>
                <span className="vm-option-label">{t('export.orientation')}</span>
                <div className="vm-options" role="group" aria-label={t('export.orientation')}>
                  {ORIENTATIONS.map((o) =>
                    optionButton(
                      o,
                      o === 'portrait' ? t('export.portrait') : t('export.landscape'),
                      orientation === o,
                      () => update({ orientation: o }),
                      orientationLocked,
                    ),
                  )}
                </div>
              </div>
            )}
            <div className="vm-option-group">
              <span className="vm-option-label">{t('export.qualityLabel')}</span>
              <div className="vm-options" role="group" aria-label={t('export.qualityLabel')}>
                {QUALITIES.map((q) => optionButton(String(q), `${q}x`, quality === q, () => update({ quality: q })))}
              </div>
            </div>
            <button type="button" className="vm-btn vm-btn-coral" onClick={onExport} disabled={exporting}>
              <Icon name="download" size={20} />
              {exporting ? t('export.exporting') : t('export.short')}
            </button>
          </div>
        </section>

        <section className="vm-settings-section">
          <SectionLabel>{t('settings.data')}</SectionLabel>
          <DataImportExportControls />
          <div className="vm-settings-hint">{isDesktop ? t('data.hint') : t('settings.dataHint')}</div>
        </section>
      </div>
    </div>
  );
}
