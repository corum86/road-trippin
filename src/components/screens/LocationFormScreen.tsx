import { useEffect, useState } from 'react';
import { v4 as uuidv4 } from 'uuid';
import type {
  Destination,
  DestinationDraft,
  LinkItem,
  MainLocation,
  Photo,
  PickedLocation,
} from '../../types/models';
import { useI18n } from '../../i18n/context';
import { Icon } from '../ui/Icon';
import { SectionLabel } from '../ui/SectionLabel';

type LocationFormScreenProps = {
  /** mobile: full-screen layer; desktop: floating panel over the map */
  variant: 'mobile' | 'desktop';
  /** desktop picks on the live map beside the form; the button reflects it */
  picking?: boolean;
  pickedLocation: PickedLocation | null;
  onConsumePickedLocation: () => void;
  onStartPicking: () => void;
  onClose: () => void;
} & (
  | { kind: 'destination'; initial: Destination | null; onSave: (draft: DestinationDraft) => void }
  | { kind: 'home'; initial: MainLocation | null; onSave: (home: MainLocation) => void }
);

/** Accepts "39.5", " 39.5 " and the decimal comma Greek keyboards produce. */
function parseCoordinate(text: string, limit: number): number | null {
  const trimmed = text.trim().replace(',', '.');
  if (trimmed === '') return null;
  const value = Number(trimmed);
  return Number.isFinite(value) && Math.abs(value) <= limit ? value : null;
}

export function LocationFormScreen(props: LocationFormScreenProps) {
  const { variant, picking = false, pickedLocation, onConsumePickedLocation, onStartPicking, onClose } = props;
  const { t } = useI18n();
  const isDesktop = variant === 'desktop';
  const isDestination = props.kind === 'destination';
  const dest = props.kind === 'destination' ? props.initial : null;
  const initialLocation = props.initial?.location;

  const [name, setName] = useState(props.initial?.name ?? '');
  const [lat, setLat] = useState(initialLocation ? String(initialLocation.lat) : '');
  const [lng, setLng] = useState(initialLocation ? String(initialLocation.lng) : '');
  const [attractions, setAttractions] = useState(dest?.attractions.join('\n') ?? '');
  const [notes, setNotes] = useState(dest?.notes ?? '');
  const [photos, setPhotos] = useState<Photo[]>(dest?.photos ?? []);
  const [links, setLinks] = useState<LinkItem[]>(dest?.links ?? []);
  const [locationError, setLocationError] = useState(false);

  useEffect(() => {
    if (pickedLocation) {
      // a town picked by name names the place too; a bare map point keeps the name
      if (pickedLocation.name) setName(pickedLocation.name);
      setLat(pickedLocation.lat.toFixed(5));
      setLng(pickedLocation.lng.toFixed(5));
      setLocationError(false);
      onConsumePickedLocation();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pickedLocation]);

  const title =
    props.kind === 'home'
      ? props.initial
        ? t('form.editMainLocation')
        : t('form.setHomeBase')
      : props.initial
        ? t('form.editDestination')
        : t('form.addDestination');

  function handleSave() {
    const latValue = parseCoordinate(lat, 90);
    const lngValue = parseCoordinate(lng, 180);
    if (latValue === null || lngValue === null) {
      setLocationError(true);
      return;
    }
    const location = { lat: latValue, lng: lngValue };
    if (props.kind === 'home') {
      props.onSave({ name: name.trim() || t('form.homeBase'), location });
      return;
    }
    props.onSave({
      name: name.trim() || t('form.untitledDestination'),
      location,
      attractions: attractions
        .split('\n')
        .map((a) => a.trim())
        .filter(Boolean),
      photos: photos.filter((p) => p.url.trim()),
      links: links.filter((l) => l.url.trim() && l.label.trim()),
      notes: notes.trim() || undefined,
      routeInfo: props.initial?.routeInfo,
    });
  }

  return (
    <div className={isDesktop ? 'vm-desktop-floating vm-form vm-desktop-form' : 'vm-layer vm-form'}>
      {isDesktop ? (
        <div className="vm-form-bar vm-desktop-form-bar">
          <h1 className="vm-form-title">{title}</h1>
          <button type="button" className="vm-circle-btn vm-desktop-form-close" aria-label={t('form.cancel')} onClick={onClose}>
            <Icon name="close" size={22} />
          </button>
        </div>
      ) : (
        <div className="vm-form-bar">
          <button type="button" className="vm-circle-btn vm-form-close" aria-label={t('form.cancel')} onClick={onClose}>
            <Icon name="close" size={24} />
          </button>
          <h1 className="vm-form-title">{title}</h1>
          <button type="button" className="vm-save-btn" onClick={handleSave}>
            {t('form.save')}
          </button>
        </div>
      )}

      <form
        className="vm-form-body"
        onSubmit={(e) => {
          e.preventDefault();
          handleSave();
        }}
      >
        <label className="vm-field">
          {t('form.name')}
          <input
            className="vm-input"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={isDestination ? t('form.untitledDestination') : t('form.homeBase')}
          />
        </label>

        <div className={`vm-card vm-location-card${locationError ? ' vm-location-card-error' : ''}`}>
          <SectionLabel>{t('form.location')}</SectionLabel>
          <div className="vm-coords">
            <label className="vm-coord-field">
              {t('form.latitude')}
              <input
                className="vm-coord-input"
                value={lat}
                inputMode="decimal"
                aria-invalid={locationError}
                onChange={(e) => {
                  setLat(e.target.value);
                  setLocationError(false);
                }}
              />
            </label>
            <label className="vm-coord-field">
              {t('form.longitude')}
              <input
                className="vm-coord-input"
                value={lng}
                inputMode="decimal"
                aria-invalid={locationError}
                onChange={(e) => {
                  setLng(e.target.value);
                  setLocationError(false);
                }}
              />
            </label>
          </div>
          <button
            type="button"
            className={`vm-btn vm-btn-pick${picking ? ' vm-btn-pick-active' : ''}`}
            aria-pressed={isDesktop ? picking : undefined}
            onClick={onStartPicking}
          >
            <Icon name="pin_drop" size={20} />
            {picking ? t('form.pickingNow') : t('form.pickOnMapShort')}
          </button>
          {locationError && (
            <div className="vm-form-error" role="alert">
              {t('form.needLocation')}
            </div>
          )}
        </div>

        {isDestination && (
          <>
            <label className="vm-field">
              {t('form.attractions')}
              <textarea
                className="vm-input vm-textarea"
                value={attractions}
                onChange={(e) => setAttractions(e.target.value)}
                placeholder={t('form.onePerLine')}
                rows={3}
              />
            </label>

            <label className="vm-field">
              {t('form.notes')}
              <textarea
                className="vm-input vm-textarea"
                value={notes}
                onChange={(e) => setNotes(e.target.value)}
                rows={3}
              />
            </label>

            <fieldset className="vm-card vm-rows-card">
              <legend className="vm-section-label">{t('form.photos')}</legend>
              {photos.map((photo, i) => (
                <div className="vm-row-fields" key={photo.id}>
                  <input
                    className="vm-input vm-input-sm"
                    value={photo.url}
                    onChange={(e) => setPhotos(photos.map((p, idx) => (idx === i ? { ...p, url: e.target.value } : p)))}
                    placeholder="https://…"
                    inputMode="url"
                  />
                  <input
                    className="vm-input vm-input-sm"
                    value={photo.caption ?? ''}
                    onChange={(e) =>
                      setPhotos(photos.map((p, idx) => (idx === i ? { ...p, caption: e.target.value } : p)))
                    }
                    placeholder={t('form.captionPlaceholder')}
                  />
                  <button
                    type="button"
                    className="vm-circle-btn vm-row-remove"
                    aria-label={t('form.remove')}
                    onClick={() => setPhotos(photos.filter((_, idx) => idx !== i))}
                  >
                    <Icon name="delete" size={20} />
                  </button>
                </div>
              ))}
              <button
                type="button"
                className="vm-btn vm-btn-tint-coral vm-btn-sm"
                onClick={() => setPhotos([...photos, { id: uuidv4(), url: '', caption: '' }])}
              >
                {t('form.addPhoto')}
              </button>
            </fieldset>

            <fieldset className="vm-card vm-rows-card">
              <legend className="vm-section-label">{t('form.links')}</legend>
              {links.map((link, i) => (
                <div className="vm-row-fields" key={link.id}>
                  <input
                    className="vm-input vm-input-sm"
                    value={link.label}
                    onChange={(e) => setLinks(links.map((l, idx) => (idx === i ? { ...l, label: e.target.value } : l)))}
                    placeholder={t('form.labelPlaceholder')}
                  />
                  <input
                    className="vm-input vm-input-sm"
                    value={link.url}
                    onChange={(e) => setLinks(links.map((l, idx) => (idx === i ? { ...l, url: e.target.value } : l)))}
                    placeholder="https://…"
                    inputMode="url"
                  />
                  <button
                    type="button"
                    className="vm-circle-btn vm-row-remove"
                    aria-label={t('form.remove')}
                    onClick={() => setLinks(links.filter((_, idx) => idx !== i))}
                  >
                    <Icon name="delete" size={20} />
                  </button>
                </div>
              ))}
              <button
                type="button"
                className="vm-btn vm-btn-tint-coral vm-btn-sm"
                onClick={() => setLinks([...links, { id: uuidv4(), label: '', url: '' }])}
              >
                {t('form.addLink')}
              </button>
            </fieldset>
          </>
        )}
        {/* lets the keyboard's "Go"/Enter key submit from single-line inputs */}
        <button type="submit" hidden />
      </form>

      {isDesktop && (
        <div className="vm-desktop-floating-footer vm-desktop-form-footer">
          <button type="button" className="vm-text-btn" onClick={onClose}>
            {t('form.cancel')}
          </button>
          <button type="button" className="vm-save-btn" onClick={handleSave}>
            {t('form.save')}
          </button>
        </div>
      )}
    </div>
  );
}
