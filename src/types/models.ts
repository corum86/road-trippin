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

export interface Destination {
  id: string;
  name: string;
  location: LatLng;
  attractions: string[];
  photos: Photo[];
  links: LinkItem[];
  notes?: string;
  routeInfo?: RouteInfo;
}

export interface MainLocation {
  name: string;
  location: LatLng;
}

export interface VacationMapData {
  version: number;
  mainLocation: MainLocation;
  destinations: Destination[];
}
