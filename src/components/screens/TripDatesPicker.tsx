import { useState } from 'react';
import type { Trip } from '../../types/models';
import { useI18n } from '../../i18n/context';
import {
  addDays,
  addMonths,
  daysInMonth,
  diffDays,
  formatDate,
  formatDayLabel,
  todayIso,
  weekdayIndex,
} from '../../services/dates';
import { clampTripEnd } from '../../services/tripPlan';
import { Icon } from '../ui/Icon';
import { Sheet } from '../ui/Sheet';
import { dayCountLabel } from './destinationHelpers';

interface TripDatesPickerProps {
  /** mobile: bottom sheet; desktop: popover under the date button */
  variant: 'mobile' | 'desktop';
  trip: Trip;
  onSave: (startDate: string, endDate: string) => void;
  onClose: () => void;
}

// any Monday: seeds the weekday header so it follows the locale's letters
const A_MONDAY = '2026-07-13';

export function TripDatesPicker({ variant, trip, onSave, onClose }: TripDatesPickerProps) {
  const { t, lang } = useI18n();
  const [month, setMonth] = useState(trip.startDate.slice(0, 7));
  const [start, setStart] = useState<string | null>(trip.startDate);
  const [end, setEnd] = useState<string | null>(trip.endDate);

  function pick(date: string) {
    if (!start || end) {
      // first tap, or a tap after a full range: start over
      setStart(date);
      setEnd(null);
    } else if (date < start) {
      // "end" before the start: treat it as a better start instead
      setStart(date);
    } else {
      setEnd(date);
    }
  }

  // a lone start is a one-day trip; the range is capped at the maximum length
  const rangeEnd = start ? clampTripEnd(start, end ?? start) : null;
  const dayCount = start && rangeEnd ? diffDays(start, rangeEnd) + 1 : 0;
  const today = todayIso();

  const cells: Array<string | null> = [
    ...Array.from({ length: weekdayIndex(`${month}-01`) }, () => null),
    ...Array.from({ length: daysInMonth(month) }, (_, i) => `${month}-${String(i + 1).padStart(2, '0')}`),
  ];
  while (cells.length % 7 !== 0) cells.push(null);

  // the field the next tap will set is highlighted
  const nextIsStart = !start || !!end;

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

      <div className="vm-dates-fields">
        <div className={`vm-dates-field${nextIsStart ? ' vm-dates-field-next' : ''}`}>
          <div className="vm-dates-field-label">{t('trip.start')}</div>
          <div className="vm-dates-field-value">{start ? formatDayLabel(start, lang) : '—'}</div>
        </div>
        <div className={`vm-dates-field${nextIsStart ? '' : ' vm-dates-field-next'}`}>
          <div className="vm-dates-field-label">{t('trip.end')}</div>
          <div className="vm-dates-field-value">{end ? formatDayLabel(end, lang) : '—'}</div>
        </div>
      </div>

      <div className="vm-dates-month">
        <span className="vm-dates-month-title" aria-live="polite">
          {formatDate(`${month}-01`, lang, { month: 'long', year: 'numeric' })}
        </span>
        <button
          type="button"
          className="vm-circle-btn vm-dates-nav"
          aria-label={t('trip.prevMonth')}
          onClick={() => setMonth(addMonths(month, -1))}
        >
          <Icon name="chevron_left" size={22} />
        </button>
        <button
          type="button"
          className="vm-circle-btn vm-dates-nav"
          aria-label={t('trip.nextMonth')}
          onClick={() => setMonth(addMonths(month, 1))}
        >
          <Icon name="chevron_right" size={22} />
        </button>
      </div>

      <div className="vm-dates-grid vm-dates-weekdays" aria-hidden="true">
        {Array.from({ length: 7 }, (_, i) => (
          <span key={i}>{formatDate(addDays(A_MONDAY, i), lang, { weekday: 'narrow' })}</span>
        ))}
      </div>
      <div className="vm-dates-grid">
        {cells.map((date, i) => {
          if (!date) return <span key={i} />;
          const isStart = date === start;
          const isEnd = date === rangeEnd;
          const inRange = !!start && !!rangeEnd && date >= start && date <= rangeEnd && start !== rangeEnd;
          const classes = [
            'vm-dates-cell',
            inRange && 'vm-dates-cell-in-range',
            inRange && isStart && 'vm-dates-cell-range-start',
            inRange && isEnd && 'vm-dates-cell-range-end',
            (isStart || isEnd) && 'vm-dates-cell-endpoint',
            date === today && 'vm-dates-cell-today',
          ]
            .filter(Boolean)
            .join(' ');
          return (
            <button
              key={i}
              type="button"
              className={classes}
              aria-label={formatDate(date, lang, { weekday: 'long', day: 'numeric', month: 'long' })}
              aria-pressed={isStart || isEnd}
              onClick={() => pick(date)}
            >
              <span className="vm-dates-day">{Number(date.slice(8))}</span>
            </button>
          );
        })}
      </div>

      <div className="vm-dates-footer">
        <span className="vm-dates-count">{dayCount > 0 ? dayCountLabel(dayCount, t) : ''}</span>
        <button type="button" className="vm-text-btn" onClick={onClose}>
          {t('form.cancel')}
        </button>
        <button
          type="button"
          className="vm-save-btn vm-dates-save"
          disabled={!start}
          onClick={() => {
            if (start && rangeEnd) onSave(start, rangeEnd);
          }}
        >
          {t('form.save')}
        </button>
      </div>
    </Sheet>
  );
}
