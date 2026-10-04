import type { Photo } from '../../types/models';
import { Icon } from './Icon';

/** Square photo slot: the destination's first photo, or a striped placeholder. */
export function PhotoThumb({ photo, className }: { photo: Photo | undefined; className: string }) {
  return (
    <div className={`vm-thumb ${className}`}>
      {photo ? <img src={photo.url} alt={photo.caption ?? ''} loading="lazy" /> : <Icon name="landscape" size={22} />}
    </div>
  );
}
