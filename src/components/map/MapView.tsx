import { forwardRef, useEffect } from 'react';
import { AttributionControl, MapContainer, TileLayer, useMap, useMapEvents } from 'react-leaflet';
import type { ControlPosition } from 'leaflet';
import type { VacationMapData } from '../../types/models';
import { MainLocationMarker } from './MainLocationMarker';
import { DestinationMarker } from './DestinationMarker';
import { CurvedArrowsOverlay } from './CurvedArrowsOverlay';
import { RoutePolylines } from './RoutePolylines';
import { PlaceNamesLayer } from './PlaceNamesLayer';
import type { MapPlace } from '../../services/photonService';
import type { MapDensity, RouteDisplayMode } from '../../types/display';

interface MapViewProps {
  data: VacationMapData;
  selectedDestinationId: string | null;
  onSelectDestination: (id: string) => void;
  onEditMainLocation: () => void;
  /** any click on the map background (pins don't bubble up to it) */
  onMapClick?: (lat: number, lng: number) => void;
  /** when set, city and town names in view are shown as selectable pills */
  onSelectPlace?: (place: MapPlace) => void;
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

// where the map opens with no home base and no place to centre on
const WORLD_CENTER: [number, number] = [30, 10];
const WORLD_ZOOM = 2;

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
    onSelectPlace,
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
  const home = data.mainLocation;
  const anchor = home?.location ?? data.destinations[0]?.location;

  return (
    <div ref={ref} className="vm-map-export-root" style={frameStyle}>
      <MapContainer
        center={anchor ? [anchor.lat, anchor.lng] : WORLD_CENTER}
        zoom={anchor ? 7 : WORLD_ZOOM}
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
        {home && displayMode === 'arrows' && (
          <CurvedArrowsOverlay
            mainLocation={home}
            destinations={data.destinations}
            selectedDestinationId={selectedDestinationId}
            baseStrokeWidths={ARROW_WIDTHS[density]}
          />
        )}
        {home && displayMode === 'routes' && (
          <RoutePolylines
            mainLocation={home}
            destinations={data.destinations}
            selectedDestinationId={selectedDestinationId}
          />
        )}
        {home && (
          <MainLocationMarker
            mainLocation={home}
            onEdit={onEditMainLocation}
            showLabel={showLabels}
            density={density}
          />
        )}
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
        {onSelectPlace && <PlaceNamesLayer onSelect={onSelectPlace} />}
        <MapClickHandler onClick={onMapClick} />
        {children}
      </MapContainer>
    </div>
  );
});
