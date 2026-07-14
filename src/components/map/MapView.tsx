import { forwardRef, useEffect } from 'react';
import { MapContainer, TileLayer, useMap, useMapEvents } from 'react-leaflet';
import type { VacationMapData } from '../../types/models';
import { MainLocationMarker } from './MainLocationMarker';
import { DestinationMarker } from './DestinationMarker';
import { CurvedArrowsOverlay } from './CurvedArrowsOverlay';

interface MapViewProps {
  data: VacationMapData;
  selectedDestinationId: string | null;
  onSelectDestination: (id: string) => void;
  onEditMainLocation: () => void;
  onMapClickWhilePicking?: (lat: number, lng: number) => void;
  frameStyle?: React.CSSProperties;
  showArrows?: boolean;
}

function MapClickHandler({ onClick }: { onClick?: (lat: number, lng: number) => void }) {
  useMapEvents({
    click: (e) => onClick?.(e.latlng.lat, e.latlng.lng),
  });
  return null;
}

// Leaflet only watches window resizes; when the container is resized by the
// aspect-ratio frame it needs an explicit invalidateSize (which also fires
// the 'resize' map event the arrows overlay listens to).
function MapResizeHandler() {
  const map = useMap();
  useEffect(() => {
    const observer = new ResizeObserver(() => map.invalidateSize());
    observer.observe(map.getContainer());
    return () => observer.disconnect();
  }, [map]);
  return null;
}

export const MapView = forwardRef<HTMLDivElement, MapViewProps>(function MapView(
  {
    data,
    selectedDestinationId,
    onSelectDestination,
    onEditMainLocation,
    onMapClickWhilePicking,
    frameStyle,
    showArrows = true,
  },
  ref,
) {
  const center: [number, number] = [data.mainLocation.location.lat, data.mainLocation.location.lng];

  return (
    <div ref={ref} className="vm-map-export-root" style={frameStyle}>
      <MapContainer center={center} zoom={7} className="vm-map-container" scrollWheelZoom>
        <MapResizeHandler />
        <TileLayer
          url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
          crossOrigin="anonymous"
        />
        {showArrows && (
          <CurvedArrowsOverlay
            mainLocation={data.mainLocation}
            destinations={data.destinations}
            selectedDestinationId={selectedDestinationId}
          />
        )}
        <MainLocationMarker mainLocation={data.mainLocation} onEdit={onEditMainLocation} />
        {data.destinations.map((dest) => (
          <DestinationMarker
            key={dest.id}
            destination={dest}
            selected={dest.id === selectedDestinationId}
            onSelect={onSelectDestination}
          />
        ))}
        <MapClickHandler onClick={onMapClickWhilePicking} />
      </MapContainer>
    </div>
  );
});
