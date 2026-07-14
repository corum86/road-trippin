import { useEffect, useState } from 'react';
import type { Destination, MainLocation } from '../../types/models';
import { fetchRoute } from '../../services/osrmService';
import { useMapDataStore } from '../../store/mapDataStore';
import { PhotoGallery } from './PhotoGallery';
import { LinksList } from './LinksList';

interface DestinationDetailPanelProps {
  destination: Destination;
  mainLocation: MainLocation;
  onEdit: () => void;
  onDelete: () => void;
  onClose: () => void;
}

function formatDistance(meters: number): string {
  return `${(meters / 1000).toFixed(1)} km`;
}

function formatDuration(seconds: number): string {
  const totalMinutes = Math.round(seconds / 60);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours === 0) return `${minutes} min`;
  return `${hours} h ${minutes} min`;
}

export function DestinationDetailPanel({
  destination,
  mainLocation,
  onEdit,
  onDelete,
  onClose,
}: DestinationDetailPanelProps) {
  const setRouteInfo = useMapDataStore((s) => s.setRouteInfo);
  const [routeLoading, setRouteLoading] = useState(false);

  useEffect(() => {
    if (destination.routeInfo) return;
    let cancelled = false;
    setRouteLoading(true);
    fetchRoute(mainLocation.location, destination.location)
      .then((info) => {
        if (!cancelled) setRouteInfo(destination.id, info);
      })
      .finally(() => {
        if (!cancelled) setRouteLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // Only re-run when the selected destination changes, not on every routeInfo write.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [destination.id]);

  const { routeInfo } = destination;

  return (
    <div className="vm-detail-panel">
      <div className="vm-detail-header">
        <h2>{destination.name}</h2>
        <button type="button" className="vm-icon-btn" onClick={onClose} aria-label="Close">
          ✕
        </button>
      </div>

      <div className="vm-route-info">
        {routeLoading && !routeInfo && <span className="vm-route-loading">Calculating route…</span>}
        {routeInfo && (
          <span className={routeInfo.source === 'straight-line-estimate' ? 'vm-route-estimate' : 'vm-route-real'}>
            {formatDistance(routeInfo.distanceMeters)} · {formatDuration(routeInfo.durationSeconds)}
            {routeInfo.source === 'straight-line-estimate' && (
              <em> (estimated, direct routing unavailable)</em>
            )}
          </span>
        )}
      </div>

      {destination.attractions.length > 0 && (
        <div className="vm-detail-section">
          <h3>Main attractions</h3>
          <ul>
            {destination.attractions.map((a, i) => (
              <li key={i}>{a}</li>
            ))}
          </ul>
        </div>
      )}

      {destination.notes && (
        <div className="vm-detail-section">
          <h3>Notes</h3>
          <p>{destination.notes}</p>
        </div>
      )}

      {destination.photos.length > 0 && (
        <div className="vm-detail-section">
          <h3>Photos</h3>
          <PhotoGallery photos={destination.photos} />
        </div>
      )}

      {destination.links.length > 0 && (
        <div className="vm-detail-section">
          <h3>Links</h3>
          <LinksList links={destination.links} />
        </div>
      )}

      <div className="vm-detail-actions">
        <button type="button" onClick={onEdit}>
          Edit
        </button>
        <button type="button" className="vm-btn-danger" onClick={onDelete}>
          Delete
        </button>
      </div>
    </div>
  );
}
