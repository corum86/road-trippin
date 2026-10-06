import { useState } from 'react';
import type { Trip } from '../../types/models';
import { useI18n } from '../../i18n/context';
import { diffDays, todayIso } from '../../services/dates';
import { Sheet } from '../ui/Sheet';
import { dayCountLabel } from './destinationHelpers';
import { DateRangeFields, MonthCalendar } from './RangeCalendar';
import { pickRangeDate, rangeEndOf, type DateRangeSelection } from './rangeSelection';

interface TripDatesPickerProps {
  /** mobile: bottom sheet; desktop: popover under the date button */
  variant: 'mobile' | 'desktop';
  trip: Trip;
  onSave: (startDate: string, endDate: string) => void;
  onClose: () => void;
}

export function TripDatesPicker({ variant, trip, onSave, onClose }: TripDatesPickerProps) {
  const { t } = useI18n();
  const [month, setMonth] = useState((trip.startDate ?? todayIso()).slice(0, 7));
  const [range, setRange] = useState<DateRangeSelection>({ start: trip.startDate, end: trip.endDate });

  const end = rangeEndOf(range);
  const dayCount = range.start && end ? diffDays(range.start, end) + 1 : 0;

  return (
    <Sheet
      layout={variant === 'desktop' ? 'popover' : 'bottom'}
      onClose={onClose}
      labelledBy="vm-trip-dates-title"
      className="vm-dates"
    >
      <h2 id="vm-trip-dates-title" className="vm-sheet-title">
        {t('trip.datesTitle')}
      </h2>
      <p className="vm-sheet-hint">{t('trip.tapFirstLast')}</p>

      <DateRangeFields range={range} />
      <MonthCalendar
        month={month}
        onMonthChange={setMonth}
        range={range}
        onPick={(date) => setRange((current) => pickRangeDate(current, date))}
      />

      <div className="vm-dates-footer">
        <span className="vm-dates-count">{dayCount > 0 ? dayCountLabel(dayCount, t) : ''}</span>
        <button type="button" className="vm-text-btn" onClick={onClose}>
          {t('form.cancel')}
        </button>
        <button
          type="button"
          className="vm-save-btn vm-dates-save"
          disabled={!range.start}
          onClick={() => {
            if (range.start && end) onSave(range.start, end);
          }}
        >
          {t('form.save')}
        </button>
      </div>
    </Sheet>
  );
}
