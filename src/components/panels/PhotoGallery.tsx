import { useState } from 'react';
import type { Photo } from '../../types/models';

interface PhotoGalleryProps {
  photos: Photo[];
}

export function PhotoGallery({ photos }: PhotoGalleryProps) {
  const [lightboxPhoto, setLightboxPhoto] = useState<Photo | null>(null);

  if (photos.length === 0) return null;

  return (
    <div className="vm-photo-gallery">
      <div className="vm-photo-grid">
        {photos.map((photo) => (
          <button
            key={photo.id}
            type="button"
            className="vm-photo-thumb-btn"
            onClick={() => setLightboxPhoto(photo)}
            aria-label={photo.caption ? `Enlarge photo: ${photo.caption}` : 'Enlarge photo'}
          >
            <img src={photo.url} alt={photo.caption ?? ''} loading="lazy" className="vm-photo-thumb" />
          </button>
        ))}
      </div>

      {lightboxPhoto && (
        <div className="vm-lightbox-backdrop" onClick={() => setLightboxPhoto(null)}>
          <div className="vm-lightbox-content" onClick={(e) => e.stopPropagation()}>
            <img src={lightboxPhoto.url} alt={lightboxPhoto.caption ?? ''} />
            {lightboxPhoto.caption && <p className="vm-lightbox-caption">{lightboxPhoto.caption}</p>}
            <button type="button" className="vm-lightbox-close" onClick={() => setLightboxPhoto(null)}>
              Close
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
