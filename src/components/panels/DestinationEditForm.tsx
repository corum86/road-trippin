import { useEffect, useState } from 'react';
import { v4 as uuidv4 } from 'uuid';
import type { Destination, LatLng, LinkItem, Photo } from '../../types/models';

interface DestinationEditFormProps {
  initial: Destination | null;
  pickedLocation: LatLng | null;
  onConsumePickedLocation: () => void;
  onStartPicking: () => void;
  onSave: (dest: Omit<Destination, 'id'>) => void;
  onCancel: () => void;
}

export function DestinationEditForm({
  initial,
  pickedLocation,
  onConsumePickedLocation,
  onStartPicking,
  onSave,
  onCancel,
}: DestinationEditFormProps) {
  const [name, setName] = useState(initial?.name ?? '');
  const [lat, setLat] = useState(initial?.location.lat ?? 0);
  const [lng, setLng] = useState(initial?.location.lng ?? 0);
  const [attractions, setAttractions] = useState<string[]>(initial?.attractions ?? []);
  const [photos, setPhotos] = useState<Photo[]>(initial?.photos ?? []);
  const [links, setLinks] = useState<LinkItem[]>(initial?.links ?? []);
  const [notes, setNotes] = useState(initial?.notes ?? '');

  useEffect(() => {
    if (pickedLocation) {
      setLat(pickedLocation.lat);
      setLng(pickedLocation.lng);
      onConsumePickedLocation();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pickedLocation]);

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    onSave({
      name: name.trim() || 'Untitled destination',
      location: { lat, lng },
      attractions: attractions.map((a) => a.trim()).filter(Boolean),
      photos: photos.filter((p) => p.url.trim()),
      links: links.filter((l) => l.url.trim() && l.label.trim()),
      notes: notes.trim() || undefined,
      routeInfo: initial?.routeInfo,
    });
  }

  return (
    <form className="vm-edit-form" onSubmit={handleSubmit}>
      <h2>{initial ? 'Edit destination' : 'Add destination'}</h2>

      <label className="vm-field">
        Name
        <input value={name} onChange={(e) => setName(e.target.value)} required />
      </label>

      <div className="vm-field-row">
        <label className="vm-field">
          Latitude
          <input
            type="number"
            step="any"
            value={lat}
            onChange={(e) => setLat(Number(e.target.value))}
            required
          />
        </label>
        <label className="vm-field">
          Longitude
          <input
            type="number"
            step="any"
            value={lng}
            onChange={(e) => setLng(Number(e.target.value))}
            required
          />
        </label>
        <button type="button" onClick={onStartPicking} className="vm-btn-secondary">
          📍 Pick on map
        </button>
      </div>

      <fieldset className="vm-fieldset">
        <legend>Main attractions</legend>
        {attractions.map((a, i) => (
          <div className="vm-field-row" key={i}>
            <input
              value={a}
              onChange={(e) =>
                setAttractions(attractions.map((x, idx) => (idx === i ? e.target.value : x)))
              }
              placeholder="Attraction name"
            />
            <button
              type="button"
              className="vm-btn-remove"
              onClick={() => setAttractions(attractions.filter((_, idx) => idx !== i))}
            >
              Remove
            </button>
          </div>
        ))}
        <button type="button" className="vm-btn-add" onClick={() => setAttractions([...attractions, ''])}>
          + Add attraction
        </button>
      </fieldset>

      <fieldset className="vm-fieldset">
        <legend>Photos (image URL)</legend>
        {photos.map((photo, i) => (
          <div className="vm-field-row" key={photo.id}>
            <input
              value={photo.url}
              onChange={(e) =>
                setPhotos(photos.map((p, idx) => (idx === i ? { ...p, url: e.target.value } : p)))
              }
              placeholder="https://…"
            />
            <input
              value={photo.caption ?? ''}
              onChange={(e) =>
                setPhotos(photos.map((p, idx) => (idx === i ? { ...p, caption: e.target.value } : p)))
              }
              placeholder="Caption (optional)"
            />
            <button
              type="button"
              className="vm-btn-remove"
              onClick={() => setPhotos(photos.filter((_, idx) => idx !== i))}
            >
              Remove
            </button>
          </div>
        ))}
        <button
          type="button"
          className="vm-btn-add"
          onClick={() => setPhotos([...photos, { id: uuidv4(), url: '', caption: '' }])}
        >
          + Add photo
        </button>
      </fieldset>

      <fieldset className="vm-fieldset">
        <legend>Links</legend>
        {links.map((link, i) => (
          <div className="vm-field-row" key={link.id}>
            <input
              value={link.label}
              onChange={(e) =>
                setLinks(links.map((l, idx) => (idx === i ? { ...l, label: e.target.value } : l)))
              }
              placeholder="Label"
            />
            <input
              value={link.url}
              onChange={(e) =>
                setLinks(links.map((l, idx) => (idx === i ? { ...l, url: e.target.value } : l)))
              }
              placeholder="https://…"
            />
            <button
              type="button"
              className="vm-btn-remove"
              onClick={() => setLinks(links.filter((_, idx) => idx !== i))}
            >
              Remove
            </button>
          </div>
        ))}
        <button
          type="button"
          className="vm-btn-add"
          onClick={() => setLinks([...links, { id: uuidv4(), label: '', url: '' }])}
        >
          + Add link
        </button>
      </fieldset>

      <label className="vm-field">
        Notes
        <textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={3} />
      </label>

      <div className="vm-detail-actions">
        <button type="submit">Save</button>
        <button type="button" className="vm-btn-secondary" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}
