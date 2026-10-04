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

export interface ItineraryDay {
  id: string;
  /** ISO date (YYYY-MM-DD) */
  date: string;
  stopIds: string[];
  note?: string;
}

export const CURRENT_DATA_VERSION = 2;

export interface VacationMapData {
  version: number;
  tripName?: string;
  mainLocation: MainLocation;
  destinations: Destination[];
  itinerary: ItineraryDay[];
}
