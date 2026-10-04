export interface LatLng {
  lat: number;
  lng: number;
}

export interface LinkItem {
  id: string;
  label: string;
  url: string;
}

export interface Photo {
  id: string;
  url: string;
  caption?: string;
}

export interface RouteInfo {
  distanceMeters: number;
  durationSeconds: number;
  source: 'osrm' | 'straight-line-estimate';
  fetchedAt: string;
  /** Road path as [lat, lng] pairs; straight line for estimates. Absent on
   * data cached before route display was added — refetched when needed. */
  geometry?: Array<[number, number]>;
}

export type TripStatus = 'planned' | 'visited';

export type Rating = 1 | 2 | 3 | 4 | 5;

export interface VisitLog {
  /** ISO date (YYYY-MM-DD) */
  visitedOn?: string;
  rating?: Rating;
  note?: string;
  /** the traveller's own photos, as opposed to the destination's reference photos */
  photos: Photo[];
}

export interface Destination {
  id: string;
  name: string;
  location: LatLng;
  attractions: string[];
  photos: Photo[];
  links: LinkItem[];
  notes?: string;
  routeInfo?: RouteInfo;
  status: TripStatus;
  favorite: boolean;
  visit?: VisitLog;
}

/** What the edit forms produce: everything except identity and trip-tracking state. */
export type DestinationDraft = Omit<Destination, 'id' | 'status' | 'favorite' | 'visit'>;

export interface MainLocation {
  name: string;
  location: LatLng;
}

export interface Trip {
  /** empty until the traveller names it */
  name: string;
  /** ISO date (YYYY-MM-DD) */
  startDate: string;
  /** ISO date (YYYY-MM-DD), inclusive */
  endDate: string;
  /**
   * plan[dayIndex] = ordered destination ids for that day. Indexed by day
   * rather than date, so moving the trip keeps the plan. It may be longer
   * than the date range: days cut off by shortening the trip are kept (their
   * stops show as unscheduled) and come back if the trip is extended again.
   */
  plan: string[][];
}

export const CURRENT_DATA_VERSION = 3;

export interface VacationMapData {
  version: number;
  mainLocation: MainLocation;
  destinations: Destination[];
  trip: Trip;
}
