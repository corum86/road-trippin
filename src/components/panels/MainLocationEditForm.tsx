import { useEffect, useState } from 'react';
import type { LatLng, MainLocation } from '../../types/models';

interface MainLocationEditFormProps {
  initial: MainLocation;
  pickedLocation: LatLng | null;
  onConsumePickedLocation: () => void;
  onStartPicking: () => void;
  onSave: (loc: MainLocation) => void;
  onCancel: () => void;
}

export function MainLocationEditForm({
  initial,
  pickedLocation,
  onConsumePickedLocation,
  onStartPicking,
  onSave,
  onCancel,
}: MainLocationEditFormProps) {
  const [name, setName] = useState(initial.name);
  const [lat, setLat] = useState(initial.location.lat);
  const [lng, setLng] = useState(initial.location.lng);

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
    onSave({ name: name.trim() || 'Home base', location: { lat, lng } });
  }

  return (
    <form className="vm-edit-form" onSubmit={handleSubmit}>
      <h2>Edit main location</h2>

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

      <div className="vm-detail-actions">
        <button type="submit">Save</button>
        <button type="button" className="vm-btn-secondary" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}
