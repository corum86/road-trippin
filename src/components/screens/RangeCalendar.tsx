import { useI18n } from '../../i18n/context';
import {
  addDays,
  addMonths,
  daysInMonth,
  formatDate,
  formatDayLabel,
  todayIso,
  weekdayIndex,
} from '../../services/dates';
import { Icon } from '../ui/Icon';
import { rangeEndOf, type DateRangeSelection } from './rangeSelection';

// any Monday: seeds the weekday header so it follows the locale's letters
const A_MONDAY = '2026-07-13';

/** Start / End boxes; the one the next tap will set is highlighted. */
export function DateRangeFields({ range }: { range: DateRangeSelection }) {
  const { t, lang } = useI18n();
  const nextIsStart = !range.start || !!range.end;
  return (
    <div className="vm-dates-fields">
      <div className={`vm-dates-field${nextIsStart ? ' vm-dates-field-next' : ''}`}>
        <div className="vm-dates-field-label">{t('trip.start')}</div>
        <div className="vm-dates-field-value">{range.start ? formatDayLabel(range.start, lang) : '—'}</div>
      </div>
      <div className={`vm-dates-field${nextIsStart ? '' : ' vm-dates-field-next'}`}>
        <div className="vm-dates-field-label">{t('trip.end')}</div>
        <div className="vm-dates-field-value">{range.end ? formatDayLabel(range.end, lang) : '—'}</div>
      </div>
    </div>
  );
}

interface MonthCalendarProps {
  /** YYYY-MM */
  month: string;
  onMonthChange: (month: string) => void;
  range: DateRangeSelection;
  onPick: (date: string) => void;
}

/** One month, weeks starting Monday, with the picked range drawn as a band. */
export function MonthCalendar({ month, onMonthChange, range, onPick }: MonthCalendarProps) {
  const { t, lang } = useI18n();
  const start = range.start;
  const end = rangeEndOf(range);
  const today = todayIso();

  const cells: Array<string | null> = [
    ...Array.from({ length: weekdayIndex(`${month}-01`) }, () => null),
    ...Array.from({ length: daysInMonth(month) }, (_, i) => `${month}-${String(i + 1).padStart(2, '0')}`),
  ];
  while (cells.length % 7 !== 0) cells.push(null);

  return (
    <div className="vm-calendar">
      <div className="vm-dates-month">
        <span className="vm-dates-month-title" aria-live="polite">
          {formatDate(`${month}-01`, lang, { month: 'long', year: 'numeric' })}
        </span>
        <button
          type="button"
          className="vm-circle-btn vm-dates-nav"
          aria-label={t('trip.prevMonth')}
          onClick={() => onMonthChange(addMonths(month, -1))}
        >
          <Icon name="chevron_left" size={22} />
        </button>
        <button
          type="button"
          className="vm-circle-btn vm-dates-nav"
          aria-label={t('trip.nextMonth')}
          onClick={() => onMonthChange(addMonths(month, 1))}
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
          const isEnd = date === end;
          const inRange = !!start && !!end && date >= start && date <= end && start !== end;
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
              onClick={() => onPick(date)}
            >
              <span className="vm-dates-day">{Number(date.slice(8))}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
