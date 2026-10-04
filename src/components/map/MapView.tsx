import { forwardRef, useEffect } from 'react';
import { AttributionControl, MapContainer, TileLayer, useMap, useMapEvents } from 'react-leaflet';
import type { ControlPosition } from 'leaflet';
import type { VacationMapData } from '../../types/models';
import { MainLocationMarker } from './MainLocationMarker';
import { DestinationMarker } from './DestinationMarker';
import { CurvedArrowsOverlay } from './CurvedArrowsOverlay';
import { RoutePolylines } from './RoutePolylines';
import type { MapDensity, RouteDisplayMode } from '../../types/display';

interface MapViewProps {
  data: VacationMapData;
  selectedDestinationId: string | null;
  onSelectDestination: (id: string) => void;
  onEditMainLocation: () => void;
  /** any click on the map background (pins don't bubble up to it) */
  onMapClick?: (lat: number, lng: number) => void;
  frameStyle?: React.CSSProperties;
  displayMode?: RouteDisplayMode;
  /** permanent name pills beside every pin instead of click popups */
  showLabels?: boolean;
  density?: MapDensity;
  zoomControl?: boolean;
  attributionPosition?: ControlPosition;
  /** extra react-leaflet children, e.g. viewport helpers */
  children?: React.ReactNode;
}

// desktop draws slightly heavier arrows to match its larger pins
const ARROW_WIDTHS: Record<MapDensity, [number, number]> = {
  compact: [2.5, 3.5],
  comfortable: [3, 4],
};

function MapClickHandler({ onClick }: { onClick?: (lat: number, lng: number) => void }) {
  useMapEvents({
    click: (e) => onClick?.(e.latlng.lat, e.latlng.lng),
  });
  return null;
}

// Leaflet only watches window resizes; when the container is resized (side
// panel opening, export sizing) it needs an explicit invalidateSize (which
// also fires the 'resize' map event the arrows overlay listens to).
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
    onMapClick,
    frameStyle,
    displayMode = 'arrows',
    showLabels = false,
    density = 'compact',
    zoomControl = true,
    attributionPosition = 'bottomright',
    children,
  },
  ref,
) {
  const center: [number, number] = [data.mainLocation.location.lat, data.mainLocation.location.lng];

  return (
    <div ref={ref} className="vm-map-export-root" style={frameStyle}>
      <MapContainer
        center={center}
        zoom={7}
        className="vm-map-container"
        scrollWheelZoom
        zoomControl={zoomControl}
        attributionControl={false}
      >
        <MapResizeHandler />
        <AttributionControl position={attributionPosition} />
        {/* tileSize 128 + zoomOffset 1 fetches tiles one zoom level deeper and
            shows them at half size: twice the map detail per pixel ("retina"
            tiles), which is what makes high-resolution exports actually sharp.
            maxZoom 18 keeps requests within OSM's z19 limit. */}
        <TileLayer
          url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
          crossOrigin="anonymous"
          tileSize={128}
          zoomOffset={1}
          maxZoom={18}
        />
        {displayMode === 'arrows' && (
          <CurvedArrowsOverlay
            mainLocation={data.mainLocation}
            destinations={data.destinations}
            selectedDestinationId={selectedDestinationId}
            baseStrokeWidths={ARROW_WIDTHS[density]}
          />
        )}
        {displayMode === 'routes' && (
          <RoutePolylines
            mainLocation={data.mainLocation}
            destinations={data.destinations}
            selectedDestinationId={selectedDestinationId}
          />
        )}
        <MainLocationMarker
          mainLocation={data.mainLocation}
          onEdit={onEditMainLocation}
          showLabel={showLabels}
          density={density}
        />
        {data.destinations.map((dest) => (
          <DestinationMarker
            key={dest.id}
            destination={dest}
            selected={dest.id === selectedDestinationId}
            onSelect={onSelectDestination}
            showLabel={showLabels}
            density={density}
          />
        ))}
        <MapClickHandler onClick={onMapClick} />
        {children}
      </MapContainer>
    </div>
  );
});
