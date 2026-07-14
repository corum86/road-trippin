import { useEffect, useState } from 'react';
import type { Photo } from '../../types/models';

interface PhotoGalleryProps {
  photos: Photo[];
}

function Chevron({ direction }: { direction: 'left' | 'right' }) {
  return (
    <svg viewBox="0 0 20 20" width="20" height="20" aria-hidden="true">
      <path
        d={direction === 'left' ? 'M12.5 3.5 L6 10 L12.5 16.5' : 'M7.5 3.5 L14 10 L7.5 16.5'}
        fill="none"
        stroke="currentColor"
        strokeWidth="2.2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function PhotoGallery({ photos }: PhotoGalleryProps) {
  const [lightboxIndex, setLightboxIndex] = useState<number | null>(null);

  const isOpen = lightboxIndex !== null;
  const count = photos.length;

  const showPrev = () => setLightboxIndex((i) => (i === null ? i : (i - 1 + count) % count));
  const showNext = () => setLightboxIndex((i) => (i === null ? i : (i + 1) % count));

  useEffect(() => {
    if (!isOpen) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'ArrowLeft') showPrev();
      else if (e.key === 'ArrowRight') showNext();
      else if (e.key === 'Escape') setLightboxIndex(null);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen, count]);

  if (count === 0) return null;

  const photo = lightboxIndex !== null ? photos[lightboxIndex] : null;

  return (
    <div className="vm-photo-gallery">
      <div className="vm-photo-grid">
        {photos.map((p, i) => (
          <button
            key={p.id}
            type="button"
            className="vm-photo-thumb-btn"
            onClick={() => setLightboxIndex(i)}
            aria-label={p.caption ? `Enlarge photo: ${p.caption}` : 'Enlarge photo'}
          >
            <img src={p.url} alt={p.caption ?? ''} loading="lazy" className="vm-photo-thumb" />
          </button>
        ))}
      </div>

      {photo && (
        <div className="vm-lightbox-backdrop" onClick={() => setLightboxIndex(null)}>
          {count > 1 && (
            <button
              type="button"
              className="vm-lightbox-nav vm-lightbox-nav-prev"
              aria-label="Previous photo"
              onClick={(e) => {
                e.stopPropagation();
                showPrev();
              }}
            >
              <Chevron direction="left" />
            </button>
          )}

          <div className="vm-lightbox-content" onClick={(e) => e.stopPropagation()}>
            <img src={photo.url} alt={photo.caption ?? ''} />
            <p className="vm-lightbox-caption">
              {photo.caption}
              {count > 1 && (
                <span className="vm-lightbox-counter">
                  {photo.caption ? ' · ' : ''}
                  {(lightboxIndex ?? 0) + 1} / {count}
                </span>
              )}
            </p>
            <button type="button" className="vm-lightbox-close" onClick={() => setLightboxIndex(null)}>
              Close
            </button>
          </div>

          {count > 1 && (
            <button
              type="button"
              className="vm-lightbox-nav vm-lightbox-nav-next"
              aria-label="Next photo"
              onClick={(e) => {
                e.stopPropagation();
                showNext();
              }}
            >
              <Chevron direction="right" />
            </button>
          )}
        </div>
      )}
    </div>
  );
}
