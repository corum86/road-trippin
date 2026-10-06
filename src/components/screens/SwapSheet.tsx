import { useMapDataStore } from '../../store/mapDataStore';
import type { Photo, VacationMapData } from '../../types/models';
import { useI18n } from '../../i18n/context';
import { haversineDistanceMeters } from '../../services/geo';
import { formatDuration } from '../../services/routeFormat';
import { destinationDriveMinutes, visiblePlan } from '../../services/tripPlan';
import { Sheet } from '../ui/Sheet';
import { PhotoThumb } from '../ui/PhotoThumb';
import type { ShowToast } from '../ui/useToast';

interface SwapSheetProps {
  /** mobile: bottom sheet; desktop: centred dialog */
  variant: 'mobile' | 'desktop';
  data: VacationMapData;
  dayIndex: number;
  index: number;
  onClose: () => void;
  onToast: ShowToast;
}

interface SwapCandidate {
  id: string;
  name: string;
  location: { lat: number; lng: number };
  photo?: Photo;
  /** null while there is no home base to drive from */
  driveMinutes: number | null;
  /** an unsaved planner suggestion (saved to Places when picked) */
  isSuggestion: boolean;
  blurb?: string;
  distanceKm: number;
}

/**
 * Replace one stop with another place: saved places not already on that day,
 * plus the planner's unsaved suggestions, nearest to the replaced place first.
 */
export function SwapSheet({ variant, data, dayIndex, index, onClose, onToast }: SwapSheetProps) {
  const { t } = useI18n();
  const addDestination = useMapDataStore((s) => s.addDestination);
  const removeDestination = useMapDataStore((s) => s.removeDestination);
  const swapStop = useMapDataStore((s) => s.swapStop);
  const setPlan = useMapDataStore((s) => s.setPlan);

  const home = data.mainLocation;
  const dayIds = visiblePlan(data.trip)[dayIndex] ?? [];
  const current = data.destinations.find((d) => d.id === dayIds[index]);
  if (!current) return null;

  const savedIds = new Set(data.destinations.map((d) => d.id));
  const kmFromCurrent = (location: { lat: number; lng: number }) =>
    haversineDistanceMeters(current.location, location) / 1000;
  const candidates: SwapCandidate[] = [
    ...data.destinations
      .filter((d) => d.id !== current.id && !dayIds.includes(d.id))
      .map((d) => ({
        id: d.id,
        name: d.name,
        location: d.location,
        photo: d.photos[0],
        driveMinutes: home ? destinationDriveMinutes(d, home) : null,
        isSuggestion: false,
        distanceKm: kmFromCurrent(d.location),
      })),
    ...(data.trip.suggestions ?? [])
      .filter((s) => !savedIds.has(s.id))
      .map((s) => ({
        id: s.id,
        name: s.name,
        location: s.location,
        driveMinutes: s.driveMinutes,
        isSuggestion: true,
        blurb: s.blurb,
        distanceKm: kmFromCurrent(s.location),
      })),
  ].sort((a, b) => a.distanceKm - b.distanceKm);

  function pick(candidate: SwapCandidate) {
    if (!current) return;
    const previousPlan = data.trip.plan;
    if (candidate.isSuggestion) {
      addDestination({
        id: candidate.id,
        name: candidate.name,
        location: candidate.location,
        attractions: [],
        photos: [],
        links: [],
        notes: candidate.blurb || undefined,
      });
    }
    swapStop(dayIndex, index, candidate.id);
    onClose();
    onToast(t('trip.swapped', { a: current.name, b: candidate.name }), 'success', {
      label: t('common.undo'),
      onAction: () => {
        if (candidate.isSuggestion) removeDestination(candidate.id);
        setPlan(previousPlan);
      },
    });
  }

  return (
    <Sheet
      layout={variant === 'desktop' ? 'dialog' : 'bottom'}
      onClose={onClose}
      labelledBy="vm-swap-title"
      className="vm-swap"
    >
      <div className="vm-swap-header">
        <h2 id="vm-swap-title" className="vm-sheet-title">
          {t('trip.swapTitle', { name: current.name })}
        </h2>
      </div>
      <div className="vm-swap-list">
        {candidates.map((c) => (
          <button key={c.id} type="button" className="vm-swap-row" onClick={() => pick(c)}>
            <PhotoThumb photo={c.photo} className="vm-swap-photo" />
            <span className="vm-swap-text">
              <span className="vm-swap-name">{c.name}</span>
              <span className="vm-meta">
                {c.driveMinutes === null
                  ? t('trip.swapDistNoHome', { km: Math.round(c.distanceKm), name: current.name })
                  : t('trip.swapDist', {
                      km: Math.round(c.distanceKm),
                      name: current.name,
                      t: formatDuration(c.driveMinutes * 60, t),
                    })}
              </span>
            </span>
            <span className={`vm-swap-tag${c.isSuggestion ? ' vm-swap-tag-suggestion' : ''}`}>
              {c.isSuggestion ? t('trip.newSuggestion') : t('trip.savedTag')}
            </span>
          </button>
        ))}
        {candidates.length === 0 && <div className="vm-empty">{t('trip.swapEmpty')}</div>}
      </div>
      <div className="vm-sheet-footer">
        <button type="button" className="vm-text-btn" onClick={onClose}>
          {t('form.cancel')}
        </button>
      </div>
    </Sheet>
  );
}
