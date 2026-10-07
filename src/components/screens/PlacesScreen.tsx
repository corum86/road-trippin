import { useState } from 'react';
import { useMapDataStore } from '../../store/mapDataStore';
import type { Destination, VacationMapData } from '../../types/models';
import { useI18n } from '../../i18n/context';
import type { TranslationKey } from '../../i18n/translations';
import { Icon } from '../ui/Icon';
import { PhotoThumb } from '../ui/PhotoThumb';
import { allPhotos, routeText, statusLabel, tripSummary } from './destinationHelpers';

type PlacesFilter = 'all' | 'planned' | 'visited' | 'favorites';

const FILTERS: Array<{ id: PlacesFilter; labelKey: TranslationKey; test: (d: Destination) => boolean }> = [
  { id: 'all', labelKey: 'filter.all', test: () => true },
  { id: 'planned', labelKey: 'status.planned', test: (d) => d.status === 'planned' },
  { id: 'visited', labelKey: 'status.visited', test: (d) => d.status === 'visited' },
  { id: 'favorites', labelKey: 'filter.favorites', test: (d) => d.favorite },
];

interface PlacesScreenProps {
  data: VacationMapData;
  variant: 'mobile' | 'desktop';
  onOpenDetail: (id: string) => void;
  onAdd: () => void;
  /** highlighted card (desktop, where Detail floats beside the list) */
  selectedId?: string | null;
  /** extra buttons shown beside Add, e.g. AI search */
  addActions?: React.ReactNode;
}

export function PlacesScreen({ data, variant, onOpenDetail, onAdd, selectedId, addActions }: PlacesScreenProps) {
  const { t, lang } = useI18n();
  const toggleFavorite = useMapDataStore((s) => s.toggleFavorite);
  const [filter, setFilter] = useState<PlacesFilter>('all');

  const active = FILTERS.find((f) => f.id === filter) ?? FILTERS[0];
  const visible = data.destinations.filter(active.test);
  const isDesktop = variant === 'desktop';
  const { dateRange } = tripSummary(data, lang);
  const eyebrow = [data.trip.name, dateRange].filter(Boolean).join(' · ');

  return (
    <div className="vm-screen">
      <div className="vm-scroll">
        {isDesktop ? (
          <div className="vm-desktop-panel-header">
            <div className="vm-desktop-panel-heading">
              {eyebrow && <div className="vm-desktop-eyebrow">{eyebrow}</div>}
              <h1 className="vm-desktop-panel-title">{t('tabs.places')}</h1>
            </div>
            {addActions}
            <button type="button" className="vm-desktop-add-btn" onClick={onAdd}>
              <Icon name="add" size={20} />
              {t('places.add')}
            </button>
          </div>
        ) : (
          <h1 className="vm-screen-title">{t('tabs.places')}</h1>
        )}

        <div className="vm-chips" role="group">
          {FILTERS.map((f) => (
            <button
              key={f.id}
              type="button"
              className={`vm-chip${f.id === filter ? ' vm-chip-active' : ''}`}
              aria-pressed={f.id === filter}
              onClick={() => setFilter(f.id)}
            >
              {t(f.labelKey)}
              <span className="vm-chip-count">{data.destinations.filter(f.test).length}</span>
            </button>
          ))}
        </div>

        <div className="vm-place-list">
          {visible.map((dest) => (
            <div
              key={dest.id}
              role="button"
              tabIndex={0}
              className={`vm-place-card${dest.id === selectedId ? ' vm-card-selected' : ''}`}
              aria-current={dest.id === selectedId ? 'true' : undefined}
              onClick={() => onOpenDetail(dest.id)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  onOpenDetail(dest.id);
                }
              }}
            >
              <PhotoThumb photo={allPhotos(dest)[0]} className="vm-place-photo" />
              <div className="vm-place-text">
                <div className="vm-place-name">{dest.name}</div>
                <div className="vm-meta">{routeText(dest, data.mainLocation, t)}</div>
                <div className="vm-place-tags">
                  <span className={`vm-status-chip vm-status-chip-${dest.status}`}>{statusLabel(dest, t)}</span>
                  {dest.status === 'visited' && dest.visit?.rating && (
                    <span className="vm-meta vm-place-stars">{'★'.repeat(dest.visit.rating)}</span>
                  )}
                </div>
              </div>
              <button
                type="button"
                className={`vm-heart${dest.favorite ? ' vm-heart-on' : ''}`}
                aria-label={t('detail.favorite')}
                aria-pressed={dest.favorite}
                onClick={(e) => {
                  e.stopPropagation();
                  toggleFavorite(dest.id);
                }}
              >
                <Icon name="favorite" size={22} filled={dest.favorite} />
              </button>
            </div>
          ))}
          {visible.length === 0 && <div className="vm-empty">{t('places.empty')}</div>}
        </div>
      </div>

      {!isDesktop && (
        <div className="vm-fab-bar">
          {addActions}
          <button type="button" className="vm-fab" onClick={onAdd}>
            <Icon name="add" size={24} />
            {t('form.addDestination')}
          </button>
        </div>
      )}
    </div>
  );
}
