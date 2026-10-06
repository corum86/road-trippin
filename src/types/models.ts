export interface LatLng {
  lat: number;
  lng: number;
}

/** A point chosen on the map; `name` comes with it when a town's name was picked. */
export interface PickedLocation extends LatLng {
  name?: string;
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
  /** ISO date (YYYY-MM-DD); null, together with endDate, until the traveller picks the dates */
  startDate: string | null;
  /** ISO date (YYYY-MM-DD), inclusive */
  endDate: string | null;
  /**
   * plan[dayIndex] = ordered destination ids for that day. A place may be on
   * several days (a revisit) but at most once per day. Indexed by day rather
   * than date, so moving the trip keeps the plan. It may be longer than the
   * date range: days cut off by shortening the trip are kept (their stops
   * show as unscheduled) and come back if the trip is extended again.
   */
  plan: string[][];
  /** answers from the last trip-planner run; seed the next run */
  preferences?: TripPreferences;
  /** places the planner suggested last time; swap offers the unsaved ones */
  suggestions?: PlaceSuggestion[];
}

export type TravelGroup = 'couple' | 'family' | 'friends';
export type TravelStyle = 'relaxed' | 'active' | 'sightseeing' | 'culture' | 'food' | 'nature';
export type MustHave = 'beach' | 'food' | 'kids' | 'nightlife';
export type BudgetLevel = 1 | 2 | 3;

export interface TripPreferences {
  /** one-way drive limit from the home base, in minutes; null = no limit */
  maxDriveMinutes: number | null;
  ferry: boolean;
  group: TravelGroup;
  styles: TravelStyle[];
  budget: BudgetLevel | null;
  mustHaves: MustHave[];
}

/** A candidate place from the trip planner (not necessarily saved to Places). */
export interface PlaceSuggestion {
  /** a saved destination's id when the suggestion matches one, else a fresh id */
  id: string;
  name: string;
  location: LatLng;
  blurb: string;
  styles: TravelStyle[];
  groups: TravelGroup[];
  budget: BudgetLevel;
  mustHaves: MustHave[];
  /** reached by ferry: the crossing counts as drive time */
  ferry: boolean;
  /** one-way from the home base */
  driveMinutes: number;
  distanceKm: number;
  /** drive time is a straight-line estimate (routing unavailable) */
  estimated?: boolean;
}

/** A place as the trip planner hands it over: what to save to Places. */
export interface PlannedPlace {
  id: string;
  name: string;
  location: LatLng;
  photos: Photo[];
  attractions: string[];
  links: LinkItem[];
  notes?: string;
  /** drive from home as the planner measured it (OSRM table, no road geometry) */
  routeInfo?: RouteInfo;
}

/** The trip planner's result. Plan ids refer to `places` (or saved destinations). */
export interface PlannedTrip {
  name: string;
  startDate: string;
  endDate: string;
  plan: string[][];
  places: PlannedPlace[];
  preferences: TripPreferences;
  suggestions: PlaceSuggestion[];
}

export const CURRENT_DATA_VERSION = 4;

export interface VacationMapData {
  version: number;
  /** null until the traveller sets one (a cleared map) */
  mainLocation: MainLocation | null;
  destinations: Destination[];
  trip: Trip;
}
