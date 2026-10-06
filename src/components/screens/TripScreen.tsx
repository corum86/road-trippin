import { useRef, useState } from 'react';
import {
  DndContext,
  DragOverlay,
  KeyboardSensor,
  MouseSensor,
  TouchSensor,
  pointerWithin,
  rectIntersection,
  useDraggable,
  useDroppable,
  useSensor,
  useSensors,
  type CollisionDetection,
  type DragEndEvent,
  type DragMoveEvent,
  type DragStartEvent,
} from '@dnd-kit/core';
import { useMapDataStore } from '../../store/mapDataStore';
import type { Destination, MainLocation, VacationMapData } from '../../types/models';
import { useI18n, type TranslateFn } from '../../i18n/context';
import { formatDayLabel } from '../../services/dates';
import { daysByDestination, tripDayDate, visiblePlan, type StopRef } from '../../services/tripPlan';
import { shortPlaceName } from '../../services/placeNames';
import { Icon } from '../ui/Icon';
import { dayCountLabel, routeText, tripSummary } from './destinationHelpers';

interface TripScreenProps {
  data: VacationMapData;
  onOpenDetail: (id: string) => void;
  onOpenDates: () => void;
  onAddToDay: (dayIndex: number) => void;
  onSwap: (dayIndex: number, index: number) => void;
  onReplan: () => void;
  onOpenPlanner: () => void;
  onClearTrip: () => void;
  /** highlighted stop (desktop, where Detail floats beside the timeline) */
  selectedId?: string | null;
}

/** Where a dragged visit would land if released now. */
type DropZone = { target: 'tray' } | { target: 'day'; day: number; before: number };

const TRAY_ID = 'tray';
const dayDropId = (day: number) => `day:${day}`;

// Stop cards sit inside day rows, so a pointer over a card is over both;
// the card wins (it gives an insertion point), then the tray, then the day.
const pickDropTarget: CollisionDetection = (args) => {
  const pointerHits = pointerWithin(args);
  // the keyboard sensor has no pointer; fall back to overlapping rectangles
  const hits = pointerHits.length > 0 ? pointerHits : rectIntersection(args);
  const stop = hits.find((hit) => String(hit.id).startsWith('drop:'));
  if (stop) return [stop];
  const tray = hits.find((hit) => hit.id === TRAY_ID);
  if (tray) return [tray];
  return hits.slice(0, 1);
};

export function TripScreen({
  data,
  onOpenDetail,
  onOpenDates,
  onAddToDay,
  onSwap,
  onReplan,
  onOpenPlanner,
  onClearTrip,
  selectedId,
}: TripScreenProps) {
  const { t, lang } = useI18n();
  const moveStop = useMapDataStore((s) => s.moveStop);
  const setTripName = useMapDataStore((s) => s.setTripName);

  // the visit being dragged: a stop on a day, or a place from the tray
  const [dragging, setDragging] = useState<StopRef | null>(null);
  const [zone, setZone] = useState<DropZone | null>(null);
  // the pointer's latest position, captured by collision detection
  const pointerY = useRef<number | null>(null);

  const sensors = useSensors(
    useSensor(MouseSensor, { activationConstraint: { distance: 6 } }),
    // long-press to pick up, so a plain swipe still scrolls the list
    useSensor(TouchSensor, { activationConstraint: { delay: 250, tolerance: 8 } }),
    // Space picks up and drops; Enter is left free to open the place
    useSensor(KeyboardSensor, { keyboardCodes: { start: ['Space'], cancel: ['Escape'], end: ['Space'] } }),
  );

  const { trip, mainLocation: home } = data;
  const byId = new Map(data.destinations.map((d) => [d.id, d]));
  const days = visiblePlan(trip).map((ids) =>
    ids.map((id) => byId.get(id)).filter((d): d is Destination => !!d),
  );
  const daysOf = daysByDestination(trip);
  const unscheduled = data.destinations.filter((d) => !daysOf.has(d.id));
  const { dayCount, total, visited, dateRange } = tripSummary(data, lang);
  const draggedPlace = dragging ? (byId.get(dragging.id) ?? null) : null;
  const trayVisible = unscheduled.length > 0 || dragging !== null;
  const noPlaces = data.destinations.length === 0;
  const noDates = dayCount === 0;
  // an empty trip has nothing to clear
  const blankTrip =
    trip.name === '' &&
    noDates &&
    trip.plan.every((ids) => ids.length === 0) &&
    !trip.preferences &&
    !trip.suggestions?.length;

  const collisionDetection: CollisionDetection = (args) => {
    pointerY.current = args.pointerCoordinates?.y ?? null;
    return pickDropTarget(args);
  };

  function zoneFor(event: DragMoveEvent | DragEndEvent): DropZone | null {
    const { over, active } = event;
    if (!over) return null;
    if (over.id === TRAY_ID) return { target: 'tray' };
    const target = over.data.current as { day: number; index?: number; count?: number } | undefined;
    if (!target) return null;
    if (target.index === undefined) return { target: 'day', day: target.day, before: target.count ?? 0 };
    // over a stop card: above its middle inserts before it, below inserts after
    const dragged = active.rect.current.translated;
    const y = pointerY.current ?? (dragged ? dragged.top + dragged.height / 2 : 0);
    const middle = over.rect.top + over.rect.height / 2;
    return { target: 'day', day: target.day, before: y < middle ? target.index : target.index + 1 };
  }

  function handleDragStart(event: DragStartEvent) {
    setDragging((event.active.data.current as { ref: StopRef } | undefined)?.ref ?? null);
  }

  function handleDragMove(event: DragMoveEvent) {
    const next = zoneFor(event);
    setZone((current) => (sameZone(current, next) ? current : next));
  }

  function handleDragEnd(event: DragEndEvent) {
    const target = zoneFor(event);
    if (dragging && target) {
      if (target.target === 'tray') {
        if (dragging.day !== null) moveStop(dragging, null);
      } else {
        moveStop(dragging, target.day, target.before);
      }
    }
    resetDrag();
  }

  function resetDrag() {
    setDragging(null);
    setZone(null);
  }

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={collisionDetection}
      onDragStart={handleDragStart}
      onDragMove={handleDragMove}
      onDragOver={handleDragMove}
      onDragEnd={handleDragEnd}
      onDragCancel={resetDrag}
    >
      <div className={`vm-screen vm-trip${dragging ? ' vm-trip-dragging' : ''}`}>
        <div className={`vm-scroll${trayVisible ? ' vm-trip-scroll-with-tray' : ''}`}>
          <div className="vm-trip-header">
            <TripNameInput
              key={trip.name}
              name={trip.name}
              placeholder={t('tabs.trip')}
              label={t('trip.name')}
              onCommit={setTripName}
            />
            <button type="button" className="vm-trip-new-btn" onClick={onOpenPlanner}>
              <Icon name="travel_explore" size={20} />
              {t('trip.newTrip')}
            </button>
          </div>

          <div className="vm-trip-actions">
            <button type="button" className="vm-trip-dates-btn" onClick={onOpenDates}>
              <Icon name="edit_calendar" size={20} className="vm-text-teal" />
              <span className="vm-trip-dates-range">{dateRange ?? t('trip.setDates')}</span>
              <Icon name="expand_more" size={20} className="vm-text-muted" />
            </button>
            {/* re-planning spreads places over the days by drive time from the home base */}
            <button
              type="button"
              className="vm-trip-autoplan-btn"
              disabled={noPlaces || noDates || !home}
              onClick={onReplan}
            >
              <Icon name="autorenew" size={20} />
              {t('trip.replan')}
            </button>
          </div>

          <div className="vm-trip-subline">
            {noDates ? '' : `${dayCountLabel(dayCount, t)} · `}
            {t('trip.visitedOf', { v: visited, n: total })}
          </div>
          <div
            className="vm-progress"
            role="progressbar"
            aria-valuemin={0}
            aria-valuemax={total}
            aria-valuenow={visited}
          >
            <div className="vm-progress-fill" style={{ width: `${total ? (visited / total) * 100 : 0}%` }} />
          </div>

          {noPlaces && (
            <div className="vm-trip-empty-card">
              <Icon name="auto_awesome" size={28} className="vm-text-teal" />
              <h2 className="vm-trip-empty-title">
                {home ? t('trip.planEmptyTitle', { home: shortPlaceName(home.name) }) : t('trip.planEmptyTitleNoHome')}
              </h2>
              <p className="vm-trip-empty-body">{t('trip.planEmptyBody')}</p>
              <button type="button" className="vm-btn vm-btn-primary" onClick={onOpenPlanner}>
                <Icon name="travel_explore" size={20} />
                {t('trip.startPlanner')}
              </button>
            </div>
          )}

          {noDates && !noPlaces && (
            <div className="vm-trip-empty-card">
              <Icon name="edit_calendar" size={28} className="vm-text-teal" />
              <h2 className="vm-trip-empty-title">{t('trip.noDatesTitle')}</h2>
              <p className="vm-trip-empty-body">{t('trip.noDatesBody')}</p>
              <button type="button" className="vm-btn vm-btn-primary" onClick={onOpenDates}>
                <Icon name="edit_calendar" size={20} />
                {t('trip.setDates')}
              </button>
            </div>
          )}

          <ol className="vm-timeline">
            {days.map((stops, day) => (
              <DayRow
                key={day}
                day={day}
                dateLabel={formatDayLabel(tripDayDate(trip, day), lang)}
                stops={stops}
                daysOf={daysOf}
                home={home}
                zone={zone?.target === 'day' && zone.day === day ? zone : null}
                dragging={dragging}
                selectedId={selectedId ?? null}
                t={t}
                onAdd={() => onAddToDay(day)}
                onOpenDetail={onOpenDetail}
                onSwap={(index) => onSwap(day, index)}
                onRemove={(ref) => moveStop(ref, null)}
              />
            ))}
          </ol>

          {!blankTrip && (
            <div className="vm-trip-footer">
              <button type="button" className="vm-btn vm-btn-sm vm-btn-tint-danger" onClick={onClearTrip}>
                <Icon name="delete_sweep" size={18} />
                {t('trip.clear')}
              </button>
            </div>
          )}
        </div>

        {trayVisible && (
          <Tray
            places={unscheduled}
            hot={zone?.target === 'tray'}
            dragging={dragging}
            t={t}
            onOpenDetail={onOpenDetail}
          />
        )}

        {/* the copy that follows the pointer; the original stays put, dimmed */}
        <DragOverlay dropAnimation={null}>
          {dragging && draggedPlace &&
            (dragging.day !== null ? (
              <div className="vm-stop-card vm-stop-card-overlay">
                <StopCardContent dest={draggedPlace} home={home} revisitOf={null} t={t} />
              </div>
            ) : (
              <div className="vm-trip-chip vm-trip-chip-overlay">
                <ChipContent dest={draggedPlace} />
              </div>
            ))}
        </DragOverlay>
      </div>
    </DndContext>
  );
}

function sameZone(a: DropZone | null, b: DropZone | null): boolean {
  if (a === null || b === null) return a === b;
  if (a.target === 'tray' || b.target === 'tray') return a.target === b.target;
  return a.day === b.day && a.before === b.before;
}

function isSameStop(ref: StopRef | null, day: number, index: number): boolean {
  return ref !== null && ref.day === day && 'index' in ref && ref.index === index;
}

/** Trip title, editable in place: looks like a heading, saves when it loses focus. */
function TripNameInput({
  name,
  placeholder,
  label,
  onCommit,
}: {
  name: string;
  placeholder: string;
  label: string;
  onCommit: (name: string) => void;
}) {
  const [value, setValue] = useState(name);
  return (
    <input
      className="vm-screen-title vm-trip-name"
      value={value}
      placeholder={placeholder}
      aria-label={label}
      onChange={(e) => setValue(e.target.value)}
      onBlur={() => {
        if (value.trim() !== name) onCommit(value.trim());
      }}
      onKeyDown={(e) => {
        if (e.key === 'Enter') e.currentTarget.blur();
      }}
    />
  );
}

interface DayRowProps {
  day: number;
  dateLabel: string;
  stops: Destination[];
  daysOf: Map<string, number[]>;
  home: MainLocation | null;
  /** the drop zone, when it is on this day */
  zone: { before: number } | null;
  dragging: StopRef | null;
  selectedId: string | null;
  t: TranslateFn;
  onAdd: () => void;
  onOpenDetail: (id: string) => void;
  onSwap: (index: number) => void;
  onRemove: (ref: StopRef) => void;
}

function DayRow({
  day,
  dateLabel,
  stops,
  daysOf,
  home,
  zone,
  dragging,
  selectedId,
  t,
  onAdd,
  onOpenDetail,
  onSwap,
  onRemove,
}: DayRowProps) {
  const { setNodeRef } = useDroppable({ id: dayDropId(day), data: { day, count: stops.length } });
  const count = stops.length;
  const allVisited = count > 0 && stops.every((s) => s.status === 'visited');
  const circleClass = allVisited ? ' vm-day-circle-done' : count > 0 ? ' vm-day-circle-stops' : '';

  return (
    <li ref={setNodeRef} className={`vm-day${zone ? ' vm-day-hot' : ''}`}>
      <div className="vm-day-rail">
        <div className={`vm-day-circle${circleClass}`}>
          <span className="vm-day-circle-label">{t('trip.day')}</span>
          <span className="vm-day-circle-n">{day + 1}</span>
        </div>
        <div className="vm-day-line" />
      </div>
      <div className="vm-day-body">
        <div className="vm-day-header">
          <span className="vm-day-date">{dateLabel}</span>
          {count > 0 && (
            <span className="vm-meta">{count === 1 ? t('trip.oneStop') : t('trip.nStops', { n: count })}</span>
          )}
          <button
            type="button"
            className="vm-day-add"
            aria-label={t('trip.addToDay', { n: day + 1 })}
            title={t('trip.addToDay', { n: day + 1 })}
            onClick={onAdd}
          >
            <Icon name="add" size={20} />
          </button>
        </div>
        <div className="vm-day-stops">
          {stops.map((stop, index) => {
            // a later visit of a place already planned for an earlier day
            const earlier = (daysOf.get(stop.id) ?? []).filter((d) => d < day);
            return (
              <StopCard
                key={`${stop.id}-${index}`}
                dest={stop}
                day={day}
                index={index}
                home={home}
                revisitOf={earlier.length > 0 ? earlier[0] : null}
                // 3px teal line where the dragged place would be inserted
                insertion={
                  zone?.before === index ? 'before' : zone?.before === count && index === count - 1 ? 'after' : null
                }
                isDragged={isSameStop(dragging, day, index)}
                dragActive={dragging !== null}
                selected={stop.id === selectedId}
                t={t}
                onOpen={() => onOpenDetail(stop.id)}
                onSwap={() => onSwap(index)}
                onRemove={() => onRemove({ id: stop.id, day, index })}
              />
            );
          })}
          {count === 0 && (
            <button type="button" className="vm-day-empty" onClick={onAdd}>
              {t('trip.dropHere')}
            </button>
          )}
        </div>
      </div>
    </li>
  );
}

interface StopCardProps {
  dest: Destination;
  day: number;
  index: number;
  home: MainLocation | null;
  /** index of the earlier day this place is first visited on, for a revisit */
  revisitOf: number | null;
  insertion: 'before' | 'after' | null;
  isDragged: boolean;
  dragActive: boolean;
  selected: boolean;
  t: TranslateFn;
  onOpen: () => void;
  onSwap: () => void;
  onRemove: () => void;
}

// keep a press on a card's own buttons from starting a drag of the card
const stopDragStart = {
  onPointerDown: (e: React.SyntheticEvent) => e.stopPropagation(),
  onMouseDown: (e: React.SyntheticEvent) => e.stopPropagation(),
  onTouchStart: (e: React.SyntheticEvent) => e.stopPropagation(),
  onKeyDown: (e: React.SyntheticEvent) => e.stopPropagation(),
};

function StopCard({
  dest,
  day,
  index,
  home,
  revisitOf,
  insertion,
  isDragged,
  dragActive,
  selected,
  t,
  onOpen,
  onSwap,
  onRemove,
}: StopCardProps) {
  const ref: StopRef = { id: dest.id, day, index };
  const draggable = useDraggable({ id: `stop:${day}:${index}`, data: { ref } });
  const droppable = useDroppable({ id: `drop:${day}:${index}`, data: { day, index } });
  const classes = [
    'vm-stop-card',
    selected && 'vm-card-selected',
    isDragged && 'vm-trip-dragged',
    insertion && `vm-stop-card-insert-${insertion}`,
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <div
      ref={(node) => {
        draggable.setNodeRef(node);
        droppable.setNodeRef(node);
      }}
      className={classes}
      {...draggable.attributes}
      {...draggable.listeners}
      onClick={onOpen}
      onKeyDown={(e) => {
        draggable.listeners?.onKeyDown?.(e);
        if (e.key === 'Enter' && !dragActive && e.target === e.currentTarget) onOpen();
      }}
    >
      <StopCardContent dest={dest} home={home} revisitOf={revisitOf} t={t} />
      <button
        type="button"
        className="vm-stop-action vm-stop-swap"
        aria-label={t('trip.swap')}
        title={t('trip.swap')}
        {...stopDragStart}
        onClick={(e) => {
          e.stopPropagation();
          onSwap();
        }}
      >
        <Icon name="swap_horiz" size={20} />
      </button>
      <button
        type="button"
        className="vm-stop-action vm-stop-remove"
        aria-label={t('trip.removeFromDay')}
        title={t('trip.removeFromDay')}
        {...stopDragStart}
        onClick={(e) => {
          e.stopPropagation();
          onRemove();
        }}
      >
        <Icon name="close" size={18} />
      </button>
    </div>
  );
}

function StopCardContent({
  dest,
  home,
  revisitOf,
  t,
}: {
  dest: Destination;
  home: MainLocation | null;
  revisitOf: number | null;
  t: TranslateFn;
}) {
  const visited = dest.status === 'visited';
  return (
    <>
      <Icon name="drag_indicator" size={20} className="vm-drag-handle" />
      <Icon
        name={visited ? 'check' : 'location_on'}
        size={20}
        filled
        className={visited ? 'vm-text-teal' : 'vm-text-coral'}
      />
      <span className="vm-stop-text">
        <span className="vm-stop-name">{dest.name}</span>
        <span className="vm-stop-meta">
          {revisitOf !== null && (
            <span className="vm-revisit-badge">
              <Icon name="replay" size={14} />
              {t('trip.revisitOf', { n: revisitOf + 1 })}
            </span>
          )}
          <span className="vm-meta vm-stop-route">{routeText(dest, home, t)}</span>
        </span>
      </span>
    </>
  );
}

interface TrayProps {
  places: Destination[];
  hot: boolean;
  dragging: StopRef | null;
  t: TranslateFn;
  onOpenDetail: (id: string) => void;
}

/** Places on no day. Also the drop target that takes a visit off its day. */
function Tray({ places, hot, dragging, t, onOpenDetail }: TrayProps) {
  const { setNodeRef } = useDroppable({ id: TRAY_ID });
  return (
    <div ref={setNodeRef} className={`vm-trip-tray${hot ? ' vm-trip-tray-hot' : ''}`}>
      <div className="vm-trip-tray-label">
        <Icon name="inventory_2" size={18} />
        {t('trip.unschedTray', { n: places.length })}
      </div>
      <div className="vm-trip-tray-chips">
        {places.map((dest) => (
          <TrayChip
            key={dest.id}
            dest={dest}
            isDragged={dragging?.day === null && dragging.id === dest.id}
            dragActive={dragging !== null}
            onOpen={() => onOpenDetail(dest.id)}
          />
        ))}
        {places.length === 0 && <div className="vm-trip-tray-empty">{t('trip.dropToUnschedule')}</div>}
      </div>
    </div>
  );
}

function TrayChip({
  dest,
  isDragged,
  dragActive,
  onOpen,
}: {
  dest: Destination;
  isDragged: boolean;
  dragActive: boolean;
  onOpen: () => void;
}) {
  const ref: StopRef = { id: dest.id, day: null };
  const { setNodeRef, attributes, listeners } = useDraggable({ id: `tray:${dest.id}`, data: { ref } });
  return (
    <div
      ref={setNodeRef}
      className={`vm-trip-chip${isDragged ? ' vm-trip-dragged' : ''}`}
      {...attributes}
      {...listeners}
      onClick={onOpen}
      onKeyDown={(e) => {
        listeners?.onKeyDown?.(e);
        if (e.key === 'Enter' && !dragActive) onOpen();
      }}
    >
      <ChipContent dest={dest} />
    </div>
  );
}

function ChipContent({ dest }: { dest: Destination }) {
  return (
    <>
      <Icon name="drag_indicator" size={18} className="vm-drag-handle" />
      <span className={`vm-status-dot vm-status-dot-${dest.status}`} />
      {dest.name}
    </>
  );
}
