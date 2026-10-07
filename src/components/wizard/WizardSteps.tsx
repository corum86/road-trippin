import type { BudgetLevel, MainLocation, MustHave, PlaceSuggestion, TravelGroup, TravelStyle } from '../../types/models';
import type { DestinationAiResult } from '../../types/ai';
import { useI18n, type TranslateFn } from '../../i18n/context';
import { addDays, formatDayLabel } from '../../services/dates';
import { formatDuration } from '../../services/routeFormat';
import { countFindings, domainOf } from '../../services/aiFindings';
import { dayDriveMinutes } from '../../services/tripPlan';
import type { MatchReason, RankedSuggestion } from '../../services/tripSuggestions';
import { Icon } from '../ui/Icon';
import { SectionLabel } from '../ui/SectionLabel';
import { DateRangeFields, MonthCalendar } from '../screens/RangeCalendar';
import type { DateRangeSelection } from '../screens/rangeSelection';
import {
  BUDGET_OPTIONS,
  DRIVE_OPTIONS,
  GROUP_OPTIONS,
  LENGTH_OPTIONS,
  MUST_HAVE_OPTIONS,
  STYLE_OPTIONS,
  driveLine,
  type ResearchState,
} from './wizardModel';

function placeCount(n: number, t: TranslateFn): string {
  return n === 1 ? t('wizard.onePlace') : t('wizard.nPlaces', { n });
}

// ---------------------------------------------------------------- 1 dates

interface DatesStepProps {
  home: MainLocation;
  range: DateRangeSelection;
  month: string;
  dayCount: number;
  onMonthChange: (month: string) => void;
  onPick: (date: string) => void;
  onLength: (days: number) => void;
  onChangeHome: () => void;
}

export function DatesStep({ home, range, month, dayCount, onMonthChange, onPick, onLength, onChangeHome }: DatesStepProps) {
  const { t } = useI18n();
  return (
    <>
      <div className="vm-card vm-wizard-home">
        <span className="vm-home-badge">★</span>
        <span className="vm-wizard-home-text">
          <span className="vm-section-label vm-wizard-home-label">{t('form.homeBase')}</span>
          <span className="vm-wizard-home-name">{home.name}</span>
        </span>
        <button type="button" className="vm-text-btn vm-wizard-home-change" onClick={onChangeHome}>
          {t('wizard.changeHome')}
        </button>
      </div>
      <DateRangeFields range={range} />
      <div className="vm-wizard-lengths">
        {LENGTH_OPTIONS.map((option) => {
          const active = !!range.start && dayCount === option.days;
          return (
            <button
              key={option.days}
              type="button"
              className={`vm-wizard-length${active ? ' vm-wizard-on' : ''}`}
              aria-pressed={active}
              onClick={() => onLength(option.days)}
            >
              {t(option.labelKey)}
            </button>
          );
        })}
      </div>
      <div className="vm-card vm-wizard-calendar">
        <MonthCalendar month={month} onMonthChange={onMonthChange} range={range} onPick={onPick} />
      </div>
    </>
  );
}

// ---------------------------------------------------------------- 2 drive

interface DriveStepProps {
  drive: number | null | undefined;
  ferry: boolean;
  /** reachable suggestions per drive limit; null while they load, undefined if unavailable */
  countFor: (minutes: number | null) => number | null | undefined;
  onDrive: (minutes: number | null) => void;
  onToggleFerry: () => void;
}

export function DriveStep({ drive, ferry, countFor, onDrive, onToggleFerry }: DriveStepProps) {
  const { t } = useI18n();
  return (
    <>
      <div className="vm-wizard-grid vm-wizard-grid-drive" role="radiogroup">
        {DRIVE_OPTIONS.map((option) => {
          const active = drive === option.minutes;
          const count = countFor(option.minutes);
          return (
            <button
              key={String(option.minutes)}
              type="button"
              role="radio"
              aria-checked={active}
              className={`vm-wizard-option${active ? ' vm-wizard-on' : ''}`}
              onClick={() => onDrive(option.minutes)}
            >
              <Icon
                name={active ? 'radio_button_checked' : 'radio_button_unchecked'}
                size={24}
                filled={active}
                className="vm-wizard-option-icon"
              />
              <span className="vm-wizard-option-text">
                <span className="vm-wizard-option-label">{t(option.labelKey)}</span>
                {count !== undefined && (
                  <span className="vm-meta">{count === null ? '…' : placeCount(count, t)}</span>
                )}
              </span>
            </button>
          );
        })}
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={ferry}
        className="vm-card vm-wizard-ferry"
        onClick={onToggleFerry}
      >
        <Icon name="directions_boat" size={24} className="vm-text-teal" />
        <span className="vm-wizard-option-text">
          <span className="vm-wizard-ferry-label">{t('wizard.ferry')}</span>
          <span className="vm-meta">{t('wizard.ferrySub')}</span>
        </span>
        <span className={`vm-wizard-switch${ferry ? ' vm-wizard-switch-on' : ''}`} aria-hidden="true">
          <span className="vm-wizard-switch-knob" />
        </span>
      </button>
    </>
  );
}

// ---------------------------------------------------------------- 3 group

export function GroupStep({ group, onGroup }: { group: TravelGroup | null; onGroup: (g: TravelGroup) => void }) {
  const { t } = useI18n();
  return (
    <div className="vm-wizard-grid vm-wizard-grid-group" role="radiogroup">
      {GROUP_OPTIONS.map((option) => {
        const active = group === option.id;
        return (
          <button
            key={option.id}
            type="button"
            role="radio"
            aria-checked={active}
            className={`vm-wizard-option vm-wizard-group${active ? ' vm-wizard-on' : ''}`}
            onClick={() => onGroup(option.id)}
          >
            <span className="vm-wizard-group-icon">
              <Icon name={option.icon} size={26} />
            </span>
            <span className="vm-wizard-option-text">
              <span className="vm-wizard-group-label">{t(`wizard.group.${option.id}`)}</span>
              <span className="vm-meta">{t(`wizard.group.${option.id}Desc`)}</span>
            </span>
            <Icon name="check_circle" size={24} filled className="vm-wizard-check" />
          </button>
        );
      })}
    </div>
  );
}

// ---------------------------------------------------------------- 4 style

export function StyleStep({ styles, onToggle }: { styles: TravelStyle[]; onToggle: (s: TravelStyle) => void }) {
  const { t } = useI18n();
  return (
    <div className="vm-wizard-grid vm-wizard-grid-style">
      {STYLE_OPTIONS.map((option) => {
        const active = styles.includes(option.id);
        return (
          <button
            key={option.id}
            type="button"
            aria-pressed={active}
            className={`vm-wizard-tile${active ? ' vm-wizard-on' : ''}`}
            onClick={() => onToggle(option.id)}
          >
            <Icon name={option.icon} size={28} className="vm-wizard-option-icon" />
            <span>
              <span className="vm-wizard-tile-label">{t(`wizard.style.${option.id}`)}</span>
              <span className="vm-wizard-tile-desc">{t(`wizard.style.${option.id}Desc`)}</span>
            </span>
            <Icon name="check_circle" size={22} filled className="vm-wizard-check vm-wizard-tile-check" />
          </button>
        );
      })}
    </div>
  );
}

// ---------------------------------------------------------------- 5 extras

interface ExtrasStepProps {
  budget: BudgetLevel | null;
  mustHaves: MustHave[];
  onBudget: (level: BudgetLevel) => void;
  onToggleMust: (m: MustHave) => void;
}

export function ExtrasStep({ budget, mustHaves, onBudget, onToggleMust }: ExtrasStepProps) {
  const { t } = useI18n();
  return (
    <>
      <section className="vm-wizard-section">
        <SectionLabel>{t('wizard.budget')}</SectionLabel>
        <div className="vm-wizard-budgets">
          {BUDGET_OPTIONS.map((option) => {
            const active = budget === option.level;
            return (
              <button
                key={option.level}
                type="button"
                aria-pressed={active}
                className={`vm-wizard-budget${active ? ' vm-wizard-on' : ''}`}
                onClick={() => onBudget(option.level)}
              >
                <span className="vm-wizard-budget-symbol">{option.symbol}</span>
                <span className="vm-wizard-budget-label">{t(`wizard.budget.${option.level}`)}</span>
              </button>
            );
          })}
        </div>
      </section>
      <section className="vm-wizard-section">
        <SectionLabel>{t('wizard.mustHaves')}</SectionLabel>
        <div className="vm-wizard-musts">
          {MUST_HAVE_OPTIONS.map((option) => {
            const active = mustHaves.includes(option.id);
            return (
              <button
                key={option.id}
                type="button"
                aria-pressed={active}
                className={`vm-wizard-must${active ? ' vm-wizard-on' : ''}`}
                onClick={() => onToggleMust(option.id)}
              >
                <Icon name={option.icon} size={20} />
                {t(`wizard.must.${option.id}`)}
              </button>
            );
          })}
        </div>
      </section>
    </>
  );
}

// ---------------------------------------------------------------- 6 places

function reasonLabel(reason: MatchReason, t: TranslateFn): string {
  if (reason.kind === 'style') return t(`wizard.style.${reason.value}`);
  if (reason.kind === 'group') return t(`wizard.group.${reason.value}`);
  return t(`wizard.must.${reason.value}`);
}

interface PlacesStepProps {
  home: MainLocation;
  status: 'loading' | 'ready' | 'error';
  error: string | null;
  ranked: RankedSuggestion[];
  selected: string[];
  savedIds: Set<string>;
  topIds: string[];
  thumbnails: Record<string, string>;
  dayCount: number;
  recommended: number;
  onToggle: (id: string) => void;
  onPickTop: () => void;
  onRetry: () => void;
}

export function PlacesStep({
  home,
  status,
  error,
  ranked,
  selected,
  savedIds,
  topIds,
  thumbnails,
  dayCount,
  recommended,
  onToggle,
  onPickTop,
  onRetry,
}: PlacesStepProps) {
  const { t } = useI18n();
  if (status === 'loading') {
    return (
      <div className="vm-wizard-status">
        <Icon name="progress_activity" size={28} className="vm-wizard-spinner" />
        {t('wizard.loadingSugg', { home: home.name.replace(/^.*—\s*/, '') })}
      </div>
    );
  }
  if (status === 'error') {
    return (
      <div className="vm-wizard-status vm-wizard-status-error" role="alert">
        {t('wizard.suggFailed', { error: error ?? '' })}
        <button type="button" className="vm-text-btn" onClick={onRetry}>
          {t('common.retry')}
        </button>
      </div>
    );
  }
  return (
    <>
      <div className="vm-wizard-summary">
        <span className="vm-wizard-summary-text">
          <span className="vm-wizard-summary-count">
            {selected.length > 0 ? t('wizard.selLine', { k: selected.length }) : t('wizard.selNone')}
          </span>
          <span className="vm-meta">{t('wizard.recLine', { n: dayCount, r: recommended })}</span>
        </span>
        <button type="button" className="vm-wizard-pick-top" onClick={onPickTop} disabled={ranked.length === 0}>
          <Icon name="auto_awesome" size={20} />
          {t('wizard.pickTop', { r: Math.min(recommended, ranked.length) })}
        </button>
      </div>
      <div className="vm-wizard-suggestions">
        {ranked.map(({ suggestion: s, reasons }) => {
          const active = selected.includes(s.id);
          return (
            <button
              key={s.id}
              type="button"
              role="checkbox"
              aria-checked={active}
              className={`vm-wizard-sugg${active ? ' vm-wizard-on' : ''}`}
              onClick={() => onToggle(s.id)}
            >
              <span className="vm-thumb vm-wizard-sugg-photo">
                {thumbnails[s.id] ? <img src={thumbnails[s.id]} alt="" loading="lazy" /> : <Icon name="landscape" size={22} />}
              </span>
              <span className="vm-wizard-sugg-body">
                <span className="vm-wizard-sugg-head">
                  <span className="vm-wizard-sugg-name">{s.name}</span>
                  <Icon
                    name={active ? 'check_box' : 'check_box_outline_blank'}
                    size={24}
                    filled={active}
                    className={active ? 'vm-text-teal' : 'vm-text-faint'}
                  />
                </span>
                <span className="vm-wizard-sugg-drive">
                  <Icon name={s.ferry ? 'directions_boat' : 'directions_car'} size={16} />
                  {driveLine(s, t)}
                </span>
                {s.blurb && <span className="vm-wizard-sugg-blurb">{s.blurb}</span>}
                <span className="vm-wizard-tags">
                  {topIds.includes(s.id) && reasons.length > 0 && (
                    <span className="vm-wizard-tag vm-wizard-tag-top">{t('wizard.topPick')}</span>
                  )}
                  {savedIds.has(s.id) && <span className="vm-wizard-tag vm-wizard-tag-saved">{t('trip.savedTag')}</span>}
                  {reasons.slice(0, 3).map((reason) => (
                    <span key={`${reason.kind}-${reason.value}`} className="vm-wizard-tag vm-wizard-tag-why">
                      {reasonLabel(reason, t)}
                    </span>
                  ))}
                </span>
              </span>
            </button>
          );
        })}
      </div>
      {ranked.length === 0 && <div className="vm-wizard-empty">{t('wizard.noSugg')}</div>}
    </>
  );
}

// ---------------------------------------------------------------- 7 research

interface ResearchStepProps {
  places: PlaceSuggestion[];
  research: Record<string, ResearchState>;
  onRetry: (id: string) => void;
}

export function ResearchStep({ places, research, onRetry }: ResearchStepProps) {
  const { t } = useI18n();
  return (
    <div className="vm-wizard-research">
      {places.map((place) => {
        const state = research[place.id] ?? { status: 'queued' };
        const found = state.status === 'done' ? countFindings(state.result.findings) : null;
        return (
          <div
            key={place.id}
            className={`vm-card vm-wizard-research-row${state.status === 'queued' ? ' vm-wizard-research-queued' : ''}`}
          >
            <span className="vm-wizard-research-icon">
              {state.status === 'loading' && <Icon name="progress_activity" size={24} className="vm-wizard-spinner" />}
              {state.status === 'done' && <Icon name="check_circle" size={24} filled className="vm-text-teal" />}
              {state.status === 'error' && <Icon name="error" size={24} className="vm-wizard-error-icon" />}
              {state.status === 'queued' && <Icon name="schedule" size={22} className="vm-text-faint" />}
            </span>
            <span className="vm-wizard-option-text">
              <span className="vm-wizard-research-name">{place.name}</span>
              <span className={`vm-meta${state.status === 'error' ? ' vm-wizard-research-error' : ''}`}>
                {found
                  ? t('wizard.foundFmt', { i: found.photos, t: found.facts, l: found.links })
                  : state.status === 'loading'
                    ? t('wizard.searching')
                    : state.status === 'error'
                      ? t('wizard.researchFailed', { error: state.error })
                      : t('wizard.queued')}
              </span>
            </span>
            {state.status === 'error' && (
              <button type="button" className="vm-text-btn" onClick={() => onRetry(place.id)}>
                {t('common.retry')}
              </button>
            )}
          </div>
        );
      })}
    </div>
  );
}

// ---------------------------------------------------------------- 8 review

interface ReviewStepProps {
  place: PlaceSuggestion;
  index: number;
  total: number;
  result: DestinationAiResult | null;
  /** which findings are ticked to be saved, by position */
  picks: boolean[];
  thumbnail?: string;
  onPicks: (picks: boolean[]) => void;
}

export function ReviewStep({ place, index, total, result, picks, thumbnail, onPicks }: ReviewStepProps) {
  const { t } = useI18n();
  const findings = result?.findings ?? [];
  const allOn = findings.length > 0 && findings.every((_, i) => picks[i]);
  const headerPhoto = thumbnail ?? findings.find((f) => f.photo)?.photo?.imageUrl;

  return (
    <>
      <div className="vm-wizard-review-head">
        <span className="vm-thumb vm-wizard-review-photo">
          {headerPhoto ? <img src={headerPhoto} alt="" /> : <Icon name="landscape" size={22} />}
        </span>
        <span className="vm-wizard-option-text">
          <span className="vm-wizard-review-counter">{t('wizard.placeOf', { i: index + 1, n: total })}</span>
          <span className="vm-wizard-review-name">{place.name}</span>
          <span className="vm-meta">{driveLine(place, t)}</span>
        </span>
      </div>
      {findings.length === 0 ? (
        <div className="vm-wizard-empty">{t('wizard.nothingFound')}</div>
      ) : (
        <div className="vm-wizard-tick-bar">
          <span className="vm-meta">
            {t('wizard.tickedFmt', { k: findings.filter((_, i) => picks[i]).length, n: findings.length })}
          </span>
          <button type="button" className="vm-text-btn" onClick={() => onPicks(findings.map(() => !allOn))}>
            {allOn ? t('wizard.clearAll') : t('wizard.selectAll')}
          </button>
        </div>
      )}
      <div className="vm-wizard-cards">
        {findings.map((finding, i) => {
          const on = !!picks[i];
          return (
            // the link sits beside the tick button, not inside it, so it stays a real link
            <div key={finding.id} className={`vm-wizard-card${on ? ' vm-wizard-on' : ''}`}>
              <button
                type="button"
                role="checkbox"
                aria-checked={on}
                className="vm-wizard-card-pick"
                onClick={() => onPicks(findings.map((_, j) => (j === i ? !on : !!picks[j])))}
              >
                <span className="vm-wizard-card-photo">
                  {finding.photo ? (
                    <img src={finding.photo.imageUrl} alt={finding.photo.sourceTitle} loading="lazy" />
                  ) : (
                    <Icon name="landscape" size={32} />
                  )}
                  <span className="vm-wizard-photo-badge">
                    <Icon name={on ? 'check' : 'add'} size={18} />
                  </span>
                </span>
                <span className="vm-wizard-card-body">
                  {finding.name && <span className="vm-wizard-card-name">{finding.name}</span>}
                  {finding.text && <span className="vm-wizard-card-text">{finding.text}</span>}
                </span>
              </button>
              {finding.link && (
                <a
                  className="vm-wizard-card-link"
                  href={finding.link.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  title={finding.link.label}
                >
                  <Icon name="link" size={18} />
                  <span className="vm-wizard-card-domain">{domainOf(finding.link.url)}</span>
                  <Icon name="open_in_new" size={16} />
                </a>
              )}
            </div>
          );
        })}
      </div>
    </>
  );
}

// ---------------------------------------------------------------- 9 plan

interface PlanStepProps {
  plan: string[][];
  places: Map<string, PlaceSuggestion>;
  startDate: string;
  savedCount: number;
  found: { photos: number; facts: number; links: number };
}

export function PlanStep({ plan, places, startDate, savedCount, found }: PlanStepProps) {
  const { t, lang } = useI18n();
  return (
    <>
      <div className="vm-wizard-plan-chips">
        <span className="vm-wizard-plan-chip vm-wizard-plan-chip-saved">
          <Icon name="bookmark_added" size={18} />
          {t('wizard.savedLine', { n: savedCount })}
        </span>
        <span className="vm-wizard-plan-chip">
          {t('wizard.foundLine', { i: found.photos, t: found.facts, l: found.links })}
        </span>
      </div>
      <ol className="vm-timeline vm-wizard-plan">
        {plan.map((ids, day) => {
          const stops = ids.map((id) => places.get(id)).filter((s): s is PlaceSuggestion => !!s);
          const drive = dayDriveMinutes(stops.map((s) => s.driveMinutes));
          return (
            <li key={day} className="vm-day">
              <div className="vm-day-rail">
                <div className={`vm-day-circle${stops.length > 0 ? ' vm-day-circle-stops' : ''}`}>
                  <span className="vm-day-circle-label">{t('trip.day')}</span>
                  <span className="vm-day-circle-n">{day + 1}</span>
                </div>
                <div className="vm-day-line" />
              </div>
              <div className="vm-day-body">
                <div className="vm-day-header">
                  <span className="vm-day-date">{formatDayLabel(addDays(startDate, day), lang)}</span>
                  <span className="vm-wizard-plan-drive">
                    <Icon name={drive ? 'directions_car' : 'beach_access'} size={16} />
                    {drive ? t('wizard.drivesFmt', { t: formatDuration(drive * 60, t) }) : t('wizard.noDrive')}
                  </span>
                </div>
                {stops.map((stop) => (
                  <div key={stop.id} className="vm-wizard-plan-stop">
                    <Icon name="location_on" size={20} filled className="vm-text-coral" />
                    <span className="vm-wizard-option-text">
                      <span className="vm-stop-name">{stop.name}</span>
                      <span className="vm-meta">{driveLine(stop, t)}</span>
                    </span>
                  </div>
                ))}
                {stops.length === 0 && (
                  <div className="vm-wizard-plan-free">{day === 0 ? t('wizard.arrival') : t('wizard.freeDay')}</div>
                )}
              </div>
            </li>
          );
        })}
      </ol>
      <div className="vm-card vm-wizard-note">
        <Icon name="tune" size={22} className="vm-text-teal" />
        <p>{t('wizard.adjustNote')}</p>
      </div>
    </>
  );
}
