import { Fragment } from 'react';
import { Polyline } from 'react-leaflet';
import type { Destination, MainLocation } from '../../types/models';

interface RoutePolylinesProps {
  mainLocation: MainLocation;
  destinations: Destination[];
  selectedDestinationId: string | null;
}

/**
 * Real road routes from cached OSRM geometry. Destinations whose geometry is
 * still loading (or whose route is a straight-line estimate) render as a
 * dashed straight line instead of a solid cased route.
 */
export function RoutePolylines({
  mainLocation,
  destinations,
  selectedDestinationId,
}: RoutePolylinesProps) {
  // selected last so it draws on top
  const ordered = [
    ...destinations.filter((d) => d.id !== selectedDestinationId),
    ...destinations.filter((d) => d.id === selectedDestinationId),
  ];

  return (
    <>
      {ordered.map((dest) => {
        const selected = dest.id === selectedDestinationId;
        const geometry = dest.routeInfo?.geometry;
        const isEstimate = dest.routeInfo?.source === 'straight-line-estimate';
        const dashed = !geometry || isEstimate;
        const positions: Array<[number, number]> = geometry ?? [
          [mainLocation.location.lat, mainLocation.location.lng],
          [dest.location.lat, dest.location.lng],
        ];
        const color = selected ? '#2563eb' : '#e0563f';
        const casing = selected ? '#1e40af' : '#9c3423';
        // key encodes everything that changes rendering structure, so Leaflet
        // remounts the polylines (and keeps selected on top) when it changes
        const key = `${dest.id}-${geometry ? 'geo' : 'pending'}-${dashed ? 'dash' : 'solid'}-${selected ? 'sel' : ''}`;
        return (
          <Fragment key={key}>
            {!dashed && (
              <Polyline
                positions={positions}
                pathOptions={{ color: casing, weight: selected ? 7 : 6, opacity: 0.9 }}
                interactive={false}
              />
            )}
            <Polyline
              positions={positions}
              pathOptions={{
                color,
                weight: selected ? 4 : 3.5,
                opacity: 0.9,
                dashArray: dashed ? '6 8' : undefined,
              }}
              interactive={false}
            />
          </Fragment>
        );
      })}
    </>
  );
}
