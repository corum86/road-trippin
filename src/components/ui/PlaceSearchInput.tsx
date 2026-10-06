import { useEffect, useId, useRef, useState } from 'react';
import type { LatLng } from '../../types/models';
import { useI18n } from '../../i18n/context';
import {
  SEARCH_MIN_LENGTH,
  searchPlaces,
  type PlaceMatch,
  type PlaceMatchKind,
} from '../../services/photonService';
import { Icon } from './Icon';

interface PlaceSearchInputProps {
  id?: string;
  value: string;
  onChange: (value: string) => void;
  /** a suggestion was chosen; the caller decides what it fills in */
  onSelect: (place: PlaceMatch) => void;
  placeholder?: string;
  /** places around here are suggested first */
  near?: LatLng | null;
}

// wait for a pause in typing before asking
const SEARCH_DEBOUNCE_MS = 250;

const KIND_ICON: Record<PlaceMatchKind, string> = {
  area: 'location_city',
  landmark: 'landscape',
  address: 'location_on',
};

/**
 * A text input that looks up what is typed as a place name and offers the
 * matches underneath. The text stays free: ignoring the suggestions keeps
 * whatever was typed.
 */
export function PlaceSearchInput({ id, value, onChange, onSelect, placeholder, near }: PlaceSearchInputProps) {
  const { t, lang } = useI18n();
  const listId = useId();
  const listRef = useRef<HTMLUListElement>(null);
  // what the user typed and wants matches for; text set from outside is not searched
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [results, setResults] = useState<PlaceMatch[]>([]);
  // `done`: `results` answer the current query
  const [status, setStatus] = useState<'idle' | 'loading' | 'done' | 'failed'>('idle');
  // the suggestion the arrow keys are on; -1 leaves Enter to the form
  const [active, setActive] = useState(-1);

  const nearLat = near?.lat;
  const nearLng = near?.lng;
  useEffect(() => {
    if (query.length < SEARCH_MIN_LENGTH) {
      setResults([]);
      setStatus('idle');
      return;
    }
    const controller = new AbortController();
    setStatus('loading');
    const timer = window.setTimeout(() => {
      const bias = nearLat !== undefined && nearLng !== undefined ? { lat: nearLat, lng: nearLng } : null;
      searchPlaces(query, lang, bias, controller.signal).then(
        (places) => {
          setResults(places);
          setActive(-1);
          setStatus('done');
        },
        () => {
          if (controller.signal.aborted) return;
          setResults([]);
          setStatus('failed');
        },
      );
    }, SEARCH_DEBOUNCE_MS);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [query, lang, nearLat, nearLng]);

  // the matches of the previous letters stay up while the next ones load
  let message: string | null = null;
  if (results.length === 0 && status === 'done') message = t('form.searchEmpty');
  if (results.length === 0 && status === 'failed') message = t('form.searchFailed');
  const showPopup = open && (results.length > 0 || message !== null);
  const showList = showPopup && results.length > 0;

  function choose(place: PlaceMatch) {
    onSelect(place);
    setQuery('');
    setOpen(false);
    setActive(-1);
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.nativeEvent.isComposing) return;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      if (results.length === 0) return;
      e.preventDefault();
      if (!showList) {
        setOpen(true);
        return;
      }
      // "nothing highlighted" is one of the stops on the way round
      const stops = results.length + 1;
      const next = ((active + 1 + (e.key === 'ArrowDown' ? 1 : -1) + stops) % stops) - 1;
      setActive(next);
      listRef.current?.children[next]?.scrollIntoView({ block: 'nearest' });
    } else if (e.key === 'Enter') {
      if (showList && results[active]) {
        // choose the suggestion instead of submitting the form
        e.preventDefault();
        choose(results[active]);
      }
    } else if (e.key === 'Escape' && showPopup) {
      // close the suggestions only, not the form around them
      e.stopPropagation();
      setOpen(false);
    }
  }

  return (
    <div className="vm-suggest">
      <Icon
        name={status === 'loading' ? 'progress_activity' : 'search'}
        size={20}
        className={`vm-suggest-lead${status === 'loading' ? ' vm-suggest-spinner' : ''}`}
      />
      <input
        id={id}
        className="vm-input vm-suggest-input"
        value={value}
        placeholder={placeholder}
        autoComplete="off"
        role="combobox"
        aria-autocomplete="list"
        aria-expanded={showList}
        aria-controls={showList ? listId : undefined}
        aria-activedescendant={showList && active >= 0 ? `${listId}-${active}` : undefined}
        onChange={(e) => {
          onChange(e.target.value);
          setQuery(e.target.value.trim());
          setOpen(true);
          setActive(-1);
        }}
        onFocus={() => {
          if (query !== '' && query === value.trim()) setOpen(true);
        }}
        onBlur={() => setOpen(false)}
        onKeyDown={handleKeyDown}
      />
      {showPopup && (
        // keep the focus (and the phone's keyboard) in the input while choosing
        <div className="vm-suggest-popup" onMouseDown={(e) => e.preventDefault()}>
          {showList && (
            <ul className="vm-suggest-list" id={listId} ref={listRef} role="listbox" aria-label={t('form.searchResults')}>
              {results.map((place, i) => (
                <li
                  key={place.id}
                  id={`${listId}-${i}`}
                  className={`vm-suggest-option${i === active ? ' vm-suggest-option-active' : ''}`}
                  role="option"
                  aria-selected={i === active}
                  onMouseMove={() => setActive(i)}
                  onClick={() => choose(place)}
                >
                  <Icon name={KIND_ICON[place.kind]} size={20} className="vm-suggest-icon" />
                  <span className="vm-suggest-text">
                    <span className="vm-suggest-name">{place.name}</span>
                    {place.detail && <span className="vm-suggest-detail">{place.detail}</span>}
                  </span>
                </li>
              ))}
            </ul>
          )}
          {message && (
            <div className="vm-suggest-status" role="status">
              {message}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
