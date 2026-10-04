import type { Destination, VacationMapData } from '../../types/models';
import { useI18n } from '../../i18n/context';
import { formatDayLabel } from '../../services/dates';
import { Icon } from '../ui/Icon';
import { routeText, tripSummary } from './destinationHelpers';

interface TripScreenProps {
  data: VacationMapData;
  onOpenDetail: (id: string) => void;
  /** highlighted stop (desktop, where Detail floats beside the timeline) */
  selectedId?: string | null;
}

interface DayRow {
  key: string;
  badge: string;
  dateLabel: string;
  stops: Destination[];
  note: string;
  unscheduled?: boolean;
}

export function TripScreen({ data, onOpenDetail, selectedId }: TripScreenProps) {
  const { t, lang } = useI18n();
  const byId = new Map(data.destinations.map((d) => [d.id, d]));
  const { days, total, visited, dateRange } = tripSummary(data, lang);

  const rows: DayRow[] = days.map((day, i) => ({
    key: day.id,
    badge: String(i + 1),
    dateLabel: formatDayLabel(day.date, lang),
    stops: day.stopIds.map((id) => byId.get(id)).filter((d): d is Destination => !!d),
    note: day.note || (i === 0 ? t('trip.arrive') : t('trip.freeDay')),
  }));

  const scheduled = new Set(days.flatMap((d) => d.stopIds));
  const unscheduled = data.destinations.filter((d) => !scheduled.has(d.id));
  if (unscheduled.length > 0) {
    rows.push({
      key: 'unscheduled',
      badge: '+',
      dateLabel: t('trip.unscheduled'),
      stops: unscheduled,
      note: '',
      unscheduled: true,
    });
  }

  const visitedLine = t('trip.visitedOf', { v: visited, n: total });

  return (
    <div className="vm-screen">
      <div className="vm-scroll">
        <h1 className="vm-screen-title vm-trip-title">{data.tripName || t('tabs.trip')}</h1>
        <div className="vm-trip-subline">{dateRange ? `${dateRange} · ${visitedLine}` : visitedLine}</div>
        <div
          className="vm-progress"
          role="progressbar"
          aria-valuemin={0}
          aria-valuemax={total}
          aria-valuenow={visited}
        >
          <div className="vm-progress-fill" style={{ width: `${total ? (visited / total) * 100 : 0}%` }} />
        </div>

        <ol className="vm-timeline">
          {rows.map((row) => {
            const allVisited = row.stops.length > 0 && row.stops.every((s) => s.status === 'visited');
            const circleClass = row.unscheduled
              ? 'vm-day-circle-muted'
              : allVisited
                ? 'vm-day-circle-done'
                : row.stops.length > 0
                  ? 'vm-day-circle-stops'
                  : '';
            return (
              <li key={row.key} className="vm-day">
                <div className="vm-day-rail">
                  <div className={`vm-day-circle ${circleClass}`}>
                    {!row.unscheduled && <span className="vm-day-circle-label">{t('trip.day')}</span>}
                    <span className="vm-day-circle-n">{row.badge}</span>
                  </div>
                  <div className="vm-day-line" />
                </div>
                <div className="vm-day-body">
                  <div className="vm-day-date">{row.dateLabel}</div>
                  {row.stops.length === 0 && <div className="vm-day-note">{row.note}</div>}
                  {row.stops.length > 0 && (
                    <div className="vm-day-stops">
                      {row.stops.map((stop) => (
                        <button
                          key={stop.id}
                          type="button"
                          className={`vm-stop-card${stop.id === selectedId ? ' vm-card-selected' : ''}`}
                          onClick={() => onOpenDetail(stop.id)}
                        >
                          <Icon
                            name={stop.status === 'visited' ? 'check' : 'location_on'}
                            size={20}
                            filled
                            className={stop.status === 'visited' ? 'vm-text-teal' : 'vm-text-coral'}
                          />
                          <span className="vm-stop-text">
                            <span className="vm-stop-name">{stop.name}</span>
                            <span className="vm-meta">{routeText(stop, data.mainLocation, t)}</span>
                          </span>
                          <Icon name="chevron_right" size={20} className="vm-text-faint" />
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              </li>
            );
          })}
        </ol>
      </div>
    </div>
  );
}
