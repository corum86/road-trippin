import { useMapDataStore } from '../../store/mapDataStore';
import type { VacationMapData } from '../../types/models';
import { useI18n } from '../../i18n/context';
import { formatDayLabel } from '../../services/dates';
import { daysByDestination, tripDayDate } from '../../services/tripPlan';
import { Icon } from '../ui/Icon';
import { Sheet } from '../ui/Sheet';
import { routeText } from './destinationHelpers';

interface AddToDaySheetProps {
  /** mobile: bottom sheet; desktop: centred dialog */
  variant: 'mobile' | 'desktop';
  data: VacationMapData;
  dayIndex: number;
  onClose: () => void;
}

/**
 * Checklist of every destination for one trip day. Ticking applies at once:
 * it adds a visit on this day (a revisit if the place is on other days too),
 * and unticking removes only this day's visit.
 */
export function AddToDaySheet({ variant, data, dayIndex, onClose }: AddToDaySheetProps) {
  const { t, lang } = useI18n();
  const toggleTripDay = useMapDataStore((s) => s.toggleTripDay);
  const daysOf = daysByDestination(data.trip);

  return (
    <Sheet
      layout={variant === 'desktop' ? 'dialog' : 'bottom'}
      onClose={onClose}
      labelledBy="vm-add-to-day-title"
      className="vm-add-to-day"
    >
      <div className="vm-add-to-day-header">
        <h2 id="vm-add-to-day-title" className="vm-sheet-title">
          {t('trip.addToDay', { n: dayIndex + 1 })}
        </h2>
        <p className="vm-sheet-hint">{formatDayLabel(tripDayDate(data.trip, dayIndex), lang)}</p>
        <p className="vm-add-to-day-revisit-hint">
          <Icon name="replay" size={16} />
          {t('trip.sheetHint')}
        </p>
      </div>

      <div className="vm-add-to-day-list">
        {data.destinations.map((dest) => {
          const days = daysOf.get(dest.id) ?? [];
          const onThisDay = days.includes(dayIndex);
          const otherDays = days.filter((d) => d !== dayIndex);
          return (
            <button
              key={dest.id}
              type="button"
              role="checkbox"
              aria-checked={onThisDay}
              className="vm-add-to-day-row"
              onClick={() => toggleTripDay(dest.id, dayIndex)}
            >
              <Icon
                name={onThisDay ? 'check_box' : 'check_box_outline_blank'}
                size={24}
                filled={onThisDay}
                className={onThisDay ? 'vm-text-teal' : 'vm-text-faint'}
              />
              <span className="vm-add-to-day-text">
                <span className="vm-add-to-day-name">{dest.name}</span>
                <span className="vm-meta">{routeText(dest, data.mainLocation, t)}</span>
              </span>
              {otherDays.length > 0 && (
                <span className="vm-add-to-day-tag">
                  {t('trip.alsoDay', { n: otherDays.map((d) => d + 1).join(', ') })}
                </span>
              )}
            </button>
          );
        })}
        {data.destinations.length === 0 && <div className="vm-empty">{t('places.empty')}</div>}
      </div>

      <div className="vm-add-to-day-footer">
        <button type="button" className="vm-save-btn vm-add-to-day-done" onClick={onClose}>
          {t('common.done')}
        </button>
      </div>
    </Sheet>
  );
}
