import { useState } from 'react';
import { useMapDataStore } from '../../store/mapDataStore';
import type { VacationMapData } from '../../types/models';
import { useI18n } from '../../i18n/context';
import { formatShortDate } from '../../services/dates';
import {
  firstOpenDay,
  replanPool,
  tripDayCount,
  tripDayDate,
  unscheduledDestinations,
} from '../../services/tripPlan';
import { Icon } from '../ui/Icon';
import { Sheet } from '../ui/Sheet';
import { SectionLabel } from '../ui/SectionLabel';
import type { ShowToast } from '../ui/useToast';

interface ReplanSheetProps {
  /** mobile: bottom sheet; desktop: centred dialog */
  variant: 'mobile' | 'desktop';
  data: VacationMapData;
  onClose: () => void;
  onToast: ShowToast;
}

/**
 * Re-plan the rest of the trip by drive time: earlier days and visited stops
 * stay put. Also offers the plain "schedule only the unscheduled" action.
 */
export function ReplanSheet({ variant, data, onClose, onToast }: ReplanSheetProps) {
  const { t, lang } = useI18n();
  const replan = useMapDataStore((s) => s.replan);
  const autoPlan = useMapDataStore((s) => s.autoPlan);
  const setPlan = useMapDataStore((s) => s.setPlan);

  const { trip, destinations } = data;
  const dayCount = tripDayCount(trip);
  const [fromDay, setFromDay] = useState(() => Math.min(firstOpenDay(trip, destinations), dayCount - 1));
  const [includeUnscheduled, setIncludeUnscheduled] = useState(true);

  const unscheduledCount = unscheduledDestinations(trip, destinations).length;
  const poolSize = replanPool(trip, destinations, fromDay, includeUnscheduled).length;
  const keepText = fromDay === 0 ? '' : fromDay === 1 ? t('trip.keep1') : t('trip.keepN', { k: fromDay });
  const summary = [keepText, t('trip.spread', { n: poolSize, a: fromDay + 1, b: dayCount })]
    .filter(Boolean)
    .join(' ');

  // both actions report what they did with an Undo that restores the plan
  function run(action: () => number, message: (count: number) => string) {
    const previousPlan = trip.plan;
    const count = action();
    onClose();
    if (count > 0) {
      onToast(message(count), 'success', {
        label: t('common.undo'),
        onAction: () => setPlan(previousPlan),
      });
    }
  }

  const handleReplan = () =>
    run(
      () => replan(fromDay, includeUnscheduled),
      () => t('trip.replanned', { a: fromDay + 1, b: dayCount }),
    );
  const handleScheduleOnly = () => run(autoPlan, (count) => t('trip.autoPlanned', { n: count }));

  return (
    <Sheet
      layout={variant === 'desktop' ? 'dialog' : 'bottom'}
      onClose={onClose}
      labelledBy="vm-replan-title"
      className="vm-replan"
    >
      <h2 id="vm-replan-title" className="vm-sheet-title vm-replan-title">
        {t('trip.replanTitle')}
      </h2>
      <div className="vm-replan-body">
        <div className="vm-replan-section">
          <SectionLabel>{t('trip.replanFrom')}</SectionLabel>
          <div className="vm-day-chips" role="group" aria-label={t('trip.replanFrom')}>
            {Array.from({ length: dayCount }, (_, day) => (
              <button
                key={day}
                type="button"
                className={`vm-day-chip${day === fromDay ? ' vm-day-chip-active' : ''}`}
                aria-pressed={day === fromDay}
                onClick={() => setFromDay(day)}
              >
                {t('trip.onDay', { n: day + 1 })}
                <span className="vm-day-chip-date">{formatShortDate(tripDayDate(trip, day), lang)}</span>
              </button>
            ))}
          </div>
        </div>
        <p className="vm-replan-summary">{summary}</p>
        {unscheduledCount > 0 && (
          <button
            type="button"
            role="checkbox"
            aria-checked={includeUnscheduled}
            className="vm-replan-include"
            onClick={() => setIncludeUnscheduled((v) => !v)}
          >
            <Icon
              name={includeUnscheduled ? 'check_box' : 'check_box_outline_blank'}
              size={24}
              filled={includeUnscheduled}
              className={includeUnscheduled ? 'vm-text-teal' : 'vm-text-faint'}
            />
            {t('trip.inclUnsched', { n: unscheduledCount })}
          </button>
        )}
      </div>
      <div className="vm-sheet-footer vm-replan-footer">
        {unscheduledCount > 0 && (
          <button type="button" className="vm-text-btn vm-replan-schedule-only" onClick={handleScheduleOnly}>
            {t('trip.scheduleOnly', { n: unscheduledCount })}
          </button>
        )}
        {/* Cancel and Re-plan stay together when the footer wraps */}
        <span className="vm-replan-actions">
          <button type="button" className="vm-text-btn" onClick={onClose}>
            {t('form.cancel')}
          </button>
          <button
            type="button"
            className="vm-save-btn vm-replan-go"
            disabled={poolSize === 0}
            onClick={handleReplan}
          >
            <Icon name="autorenew" size={20} />
            {t('trip.replan')}
          </button>
        </span>
      </div>
    </Sheet>
  );
}
