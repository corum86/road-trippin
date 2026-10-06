import { useRef, useState } from 'react';
import { v4 as uuidv4 } from 'uuid';
import { useMapDataStore } from '../../store/mapDataStore';
import type { Destination, MainLocation, Rating, Trip, TripStatus } from '../../types/models';
import { useI18n } from '../../i18n/context';
import { formatDayLabel, formatShortDate } from '../../services/dates';
import { daysByDestination, tripDayCount, tripDayDate } from '../../services/tripPlan';
import { estimateSuffix, formatDistance, formatDuration } from '../../services/routeFormat';
import { fileToResizedDataUrl } from '../../services/imageResize';
import { translateDestinationContent } from '../../services/geminiService';
import { PhotoGallery } from '../panels/PhotoGallery';
import { LinksList } from '../panels/LinksList';
import { Icon } from '../ui/Icon';
import { SectionLabel } from '../ui/SectionLabel';
import { allPhotos, useEnsureRoute } from './destinationHelpers';

interface DestinationDetailScreenProps {
  destination: Destination;
  home: MainLocation | null;
  trip: Trip;
  /** mobile: full-screen layer; desktop: floating panel over the map */
  variant: 'mobile' | 'desktop';
  /** back (mobile) or close (desktop) */
  onBack: () => void;
  onEdit: () => void;
  onDelete: () => void;
  onToast: (message: string, tone?: 'success' | 'error') => void;
}

const STATUS_OPTIONS: Array<{ id: TripStatus; icon: string }> = [
  { id: 'planned', icon: 'event' },
  { id: 'visited', icon: 'check_circle' },
];

const RATINGS: Rating[] = [1, 2, 3, 4, 5];

const canTranslate = !!import.meta.env.VITE_GEMINI_API_KEY;

export function DestinationDetailScreen({
  destination,
  home,
  trip,
  variant,
  onBack,
  onEdit,
  onDelete,
  onToast,
}: DestinationDetailScreenProps) {
  const { t, lang } = useI18n();
  const toggleFavorite = useMapDataStore((s) => s.toggleFavorite);
  const setStatus = useMapDataStore((s) => s.setStatus);
  const setRating = useMapDataStore((s) => s.setRating);
  const updateVisit = useMapDataStore((s) => s.updateVisit);
  const toggleTripDay = useMapDataStore((s) => s.toggleTripDay);
  const addVisitPhoto = useMapDataStore((s) => s.addVisitPhoto);
  const removeVisitPhoto = useMapDataStore((s) => s.removeVisitPhoto);
  const updateDestination = useMapDataStore((s) => s.updateDestination);
  const routeLoading = useEnsureRoute(destination, home);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [isTranslating, setIsTranslating] = useState(false);

  const { routeInfo, visit } = destination;
  const visited = destination.status === 'visited';
  const isDesktop = variant === 'desktop';
  const plannedDays = daysByDestination(trip).get(destination.id) ?? [];
  const photos = allPhotos(destination);
  const hero = photos[0];

  async function handlePhotoPicked(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;
    let url: string;
    try {
      url = await fileToResizedDataUrl(file);
    } catch {
      onToast(t('visit.photoFailed'), 'error');
      return;
    }
    const photo = { id: uuidv4(), url };
    try {
      addVisitPhoto(destination.id, photo);
    } catch {
      // persisting to localStorage threw (quota); undo so memory matches storage
      removeVisitPhoto(destination.id, photo.id);
      onToast(t('visit.storageFull'), 'error');
    }
  }

  async function handleTranslate() {
    setIsTranslating(true);
    try {
      updateDestination(destination.id, await translateDestinationContent(destination, lang));
    } catch (err) {
      onToast(err instanceof Error ? err.message : t('detail.translateFailed'), 'error');
    } finally {
      setIsTranslating(false);
    }
  }

  function statValue(value: string) {
    if (!routeInfo) return routeLoading ? <span className="vm-stat-loading">{t('detail.calculatingRoute')}</span> : '—';
    return value + estimateSuffix(routeInfo, t);
  }

  const actions = (
    <div className="vm-detail-actions">
      <button type="button" className="vm-btn vm-btn-tint-coral" onClick={onEdit}>
        {isDesktop && <Icon name="edit" size={18} />}
        {t('detail.edit')}
      </button>
      {canTranslate && (
        <button
          type="button"
          className="vm-btn vm-btn-tint-teal"
          onClick={handleTranslate}
          disabled={isTranslating}
          title={t('detail.translateTitle')}
        >
          {isTranslating ? t('detail.translating') : t('detail.translate')}
        </button>
      )}
      <button type="button" className="vm-btn vm-btn-tint-danger" onClick={onDelete}>
        {isDesktop && <Icon name="delete" size={18} />}
        {t('detail.delete')}
      </button>
    </div>
  );

  const content = (
    <>
      <div className="vm-hero">
        {hero ? (
          <img src={hero.url} alt={hero.caption ?? ''} />
        ) : (
          <Icon name="landscape" size={40} className="vm-hero-placeholder" />
        )}
        {isDesktop ? (
          <button type="button" className="vm-hero-btn vm-hero-close" aria-label={t('detail.close')} onClick={onBack}>
            <Icon name="close" size={20} />
          </button>
        ) : (
          <button type="button" className="vm-hero-btn vm-hero-back" aria-label={t('detail.back')} onClick={onBack}>
            <Icon name="arrow_back" size={22} />
          </button>
        )}
        <button
          type="button"
          className={`vm-hero-btn vm-hero-fav${destination.favorite ? ' vm-heart-on' : ''}`}
          aria-label={t('detail.favorite')}
          aria-pressed={destination.favorite}
          onClick={() => toggleFavorite(destination.id)}
        >
          <Icon name="favorite" size={isDesktop ? 20 : 22} filled={destination.favorite} />
        </button>
        {photos.length > 0 && (
          <span className="vm-hero-count">
            {photos.length === 1 ? t('photos.one') : t('photos.many', { n: photos.length })}
          </span>
        )}
      </div>

      <div className="vm-detail-body">
        <div>
          <h1 className="vm-detail-title">{destination.name}</h1>
          {home && <div className="vm-detail-from">{t('detail.fromHome', { home: home.name })}</div>}
        </div>

        <div className="vm-stats">
          <div className="vm-stat">
            <div className="vm-stat-label">{t('detail.distance')}</div>
            <div className="vm-stat-value">
              {statValue(routeInfo ? formatDistance(routeInfo.distanceMeters, t) : '')}
            </div>
          </div>
          <div className="vm-stat">
            <div className="vm-stat-label">{t('detail.drive')}</div>
            <div className="vm-stat-value">
              {statValue(routeInfo ? formatDuration(routeInfo.durationSeconds, t) : '')}
            </div>
          </div>
        </div>

        <div className="vm-segmented" role="group" aria-label={t('detail.tripStatus')}>
          {STATUS_OPTIONS.map((o) => {
            const active = destination.status === o.id;
            return (
              <button
                key={o.id}
                type="button"
                className={`vm-segment${active ? ` vm-segment-active vm-segment-${o.id}` : ''}`}
                aria-pressed={active}
                onClick={() => setStatus(destination.id, o.id)}
              >
                <Icon name={o.icon} size={18} />
                {o.id === 'visited' ? t('status.visited') : t('status.planned')}
              </button>
            );
          })}
        </div>

        <section className="vm-detail-section">
          <SectionLabel>{t('detail.tripDay')}</SectionLabel>
          {/* each day toggles on its own: a place can be visited on several days */}
          <div className="vm-day-chips" role="group" aria-label={t('detail.tripDay')}>
            {Array.from({ length: tripDayCount(trip) }, (_, day) => {
              const active = plannedDays.includes(day);
              return (
                <button
                  key={day}
                  type="button"
                  className={`vm-day-chip${active ? ' vm-day-chip-active' : ''}`}
                  aria-pressed={active}
                  onClick={() => toggleTripDay(destination.id, day)}
                >
                  {t('trip.onDay', { n: day + 1 })}
                  <span className="vm-day-chip-date">{formatShortDate(tripDayDate(trip, day), lang)}</span>
                </button>
              );
            })}
          </div>
          {tripDayCount(trip) === 0 && <div className="vm-meta vm-detail-empty">{t('detail.noTripDates')}</div>}
        </section>

        {visited && (
          <div className="vm-card vm-visit-card">
            <div className="vm-visit-header">
              <SectionLabel>{t('visit.log')}</SectionLabel>
              {visit?.visitedOn && <span className="vm-visit-date">{formatDayLabel(visit.visitedOn, lang)}</span>}
            </div>
            <div className="vm-stars">
              {RATINGS.map((n) => {
                const on = !!visit?.rating && n <= visit.rating;
                return (
                  <button
                    key={n}
                    type="button"
                    className={`vm-star${on ? ' vm-star-on' : ''}`}
                    aria-label={t('visit.rating', { n })}
                    aria-pressed={visit?.rating === n}
                    onClick={() => setRating(destination.id, n)}
                  >
                    <Icon name="star" size={isDesktop ? 26 : 28} filled={on} />
                  </button>
                );
              })}
            </div>
            <VisitNote
              key={destination.id}
              initial={visit?.note ?? ''}
              placeholder={t('visit.notePlaceholder')}
              onCommit={(note) => updateVisit(destination.id, { note: note || undefined })}
            />
            <PhotoGallery
              photos={visit?.photos ?? []}
              gridClassName="vm-visit-photos"
              trailing={
                <button
                  type="button"
                  className="vm-add-photo"
                  aria-label={t('visit.addPhoto')}
                  onClick={() => fileInputRef.current?.click()}
                >
                  <Icon name="add_a_photo" size={22} />
                </button>
              }
            />
            <input
              ref={fileInputRef}
              type="file"
              accept="image/*"
              hidden
              onChange={handlePhotoPicked}
            />
          </div>
        )}

        <section className="vm-detail-section">
          <SectionLabel>{t('detail.attractions')}</SectionLabel>
          {destination.attractions.map((a, i) => (
            <div key={i} className="vm-attraction">
              <Icon name="location_on" size={18} className="vm-text-coral" />
              <span>{a}</span>
            </div>
          ))}
          {destination.attractions.length === 0 && (
            <div className="vm-meta vm-detail-empty">{t('detail.noAttractions')}</div>
          )}
        </section>

        {destination.notes && (
          <section className="vm-detail-section">
            <SectionLabel>{t('detail.notes')}</SectionLabel>
            <p className="vm-detail-notes">{destination.notes}</p>
          </section>
        )}

        {destination.photos.length > 0 && (
          <section className="vm-detail-section">
            <SectionLabel>{t('detail.photos')}</SectionLabel>
            <PhotoGallery photos={destination.photos} gridClassName="vm-detail-photos" showCaptions />
          </section>
        )}

        {destination.links.length > 0 && (
          <section className="vm-detail-section vm-detail-links">
            <SectionLabel>{t('detail.links')}</SectionLabel>
            <LinksList links={destination.links} icon={<Icon name="open_in_new" size={18} />} />
          </section>
        )}

        {!isDesktop && actions}
      </div>
    </>
  );

  if (!isDesktop) return <div className="vm-layer vm-detail">{content}</div>;

  return (
    <div className="vm-desktop-floating vm-desktop-detail">
      <div className="vm-desktop-floating-scroll">{content}</div>
      <div className="vm-desktop-floating-footer">{actions}</div>
    </div>
  );
}

/** Inline-editable visit note: plain text look, saved when it loses focus. */
function VisitNote({
  initial,
  placeholder,
  onCommit,
}: {
  initial: string;
  placeholder: string;
  onCommit: (note: string) => void;
}) {
  const [value, setValue] = useState(initial);
  return (
    <textarea
      className="vm-visit-note"
      value={value}
      placeholder={placeholder}
      rows={1}
      onChange={(e) => setValue(e.target.value)}
      onBlur={() => {
        if (value.trim() !== initial) onCommit(value.trim());
      }}
    />
  );
}
