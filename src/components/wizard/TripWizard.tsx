import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { MutableRefObject } from 'react';
import type { MainLocation, PlaceSuggestion, PlannedTrip, TripPreferences, VacationMapData } from '../../types/models';
import { useI18n } from '../../i18n/context';
import { addDays, diffDays, formatDate, todayIso } from '../../services/dates';
import { fetchAiFindingsForDestination, GeminiApiKeyMissingError } from '../../services/geminiService';
import { fetchImagesForDestination } from '../../services/wikimediaService';
import { countFindings } from '../../services/aiFindings';
import { planDays } from '../../services/tripPlan';
import {
  isReachable,
  loadSuggestions,
  rankSuggestions,
  recommendedPlaceCount,
} from '../../services/tripSuggestions';
import { Icon } from '../ui/Icon';
import { pickRangeDate, rangeEndOf } from '../screens/rangeSelection';
import {
  DatesStep,
  DriveStep,
  ExtrasStep,
  GroupStep,
  PlacesStep,
  PlanStep,
  ResearchStep,
  ReviewStep,
  StyleStep,
} from './WizardSteps';
import {
  SEGMENTS,
  STEPS,
  defaultPicks,
  keptFindings,
  segmentOf,
  suggestionAsDestination,
  toPlannedPlace,
  type ResearchState,
  type WizardAnswers,
  type WizardStep,
} from './wizardModel';
import './TripWizard.css';

interface TripWizardProps {
  /** mobile: full-screen page; desktop: centred dialog */
  variant: 'mobile' | 'desktop';
  data: VacationMapData;
  /** the planner plans around the home base, so it only opens once there is one */
  home: MainLocation;
  onClose: () => void;
  onFinish: (planned: PlannedTrip) => void;
  /** open the home-base form (the planner plans around it) */
  onChangeHome: () => void;
  /**
   * Lets the shell's back gesture step back through the wizard. The handler
   * returns false on the first step, where back should close it instead.
   */
  backHandlerRef?: MutableRefObject<(() => boolean) | null>;
}

type Catalog =
  | { status: 'loading' }
  | { status: 'ready'; items: PlaceSuggestion[] }
  | { status: 'error'; error: string };

// the free Gemini tier allows only a few requests a minute: research one
// place at a time, this far apart (matches fetchAiFindingsForAllDestinations)
const RESEARCH_SPACING_MS = 4000;
// thumbnails for the suggestion cards, fetched a few at a time
const THUMBNAIL_BATCH = 4;

const NO_PICKS: boolean[] = [];

/**
 * Guided trip planning: dates, drive limit, group, style and extras, then
 * suggested places (Gemini, ranked on the device), research of the picked
 * places (things to do from Gemini, each with a Wikimedia photo and a link),
 * a review of what to keep, and a day-by-day plan by drive time.
 */
export function TripWizard({ variant, data, home, onClose, onFinish, onChangeHome, backHandlerRef }: TripWizardProps) {
  const { t, lang } = useI18n();
  const saved = data.trip.preferences;

  const [step, setStep] = useState<WizardStep>('dates');
  const [month, setMonth] = useState(todayIso().slice(0, 7));
  const [answers, setAnswers] = useState<WizardAnswers>(() => ({
    start: null,
    end: null,
    // a re-run starts from the last answers
    drive: saved ? saved.maxDriveMinutes : undefined,
    ferry: saved?.ferry ?? true,
    group: saved?.group ?? null,
    styles: saved?.styles ?? [],
    budget: saved?.budget ?? null,
    mustHaves: saved?.mustHaves ?? [],
  }));
  const [selected, setSelected] = useState<string[]>([]);
  const [catalog, setCatalog] = useState<Catalog>({ status: 'loading' });
  const [thumbnails, setThumbnails] = useState<Record<string, string>>({});
  const [research, setResearch] = useState<Record<string, ResearchState>>({});
  // per place: which of its findings are ticked to be saved
  const [picks, setPicks] = useState<Record<string, boolean[]>>({});
  const [reviewIndex, setReviewIndex] = useState(0);
  const scrollRef = useRef<HTMLDivElement>(null);

  // async results must not land after the wizard closed
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  const update = (patch: Partial<WizardAnswers>) => setAnswers((current) => ({ ...current, ...patch }));

  // ---- suggestions: asked once up front so the drive step can show counts
  const loadCatalog = useCallback(async () => {
    setCatalog({ status: 'loading' });
    try {
      const items = await loadSuggestions(home, data.destinations, lang);
      if (!alive.current) return;
      setCatalog({ status: 'ready', items });
      for (let i = 0; i < items.length; i += THUMBNAIL_BATCH) {
        const batch = items.slice(i, i + THUMBNAIL_BATCH);
        const images = await Promise.all(batch.map((s) => fetchImagesForDestination(suggestionAsDestination(s))));
        if (!alive.current) return;
        setThumbnails((current) => {
          const next = { ...current };
          batch.forEach((s, j) => {
            if (images[j][0]) next[s.id] = images[j][0].imageUrl;
          });
          return next;
        });
      }
    } catch (err) {
      if (!alive.current) return;
      const message =
        err instanceof GeminiApiKeyMissingError ? t('ai.unavailable') : err instanceof Error ? err.message : '';
      setCatalog({ status: 'error', error: message });
    }
    // the home base and saved places at open time are what gets planned around
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const catalogRequested = useRef(false);
  useEffect(() => {
    // StrictMode mounts twice in development; ask Gemini once
    if (catalogRequested.current) return;
    catalogRequested.current = true;
    void loadCatalog();
  }, [loadCatalog]);

  // ---- derived answers
  const range = { start: answers.start, end: answers.end };
  const endDate = rangeEndOf(range);
  const dayCount = answers.start && endDate ? diffDays(answers.start, endDate) + 1 : 0;
  const items = useMemo(() => (catalog.status === 'ready' ? catalog.items : []), [catalog]);
  const maxDrive = answers.drive === undefined ? null : answers.drive;
  const ranked = useMemo(
    () =>
      rankSuggestions(
        items.filter((s) => isReachable(s, maxDrive, answers.ferry)),
        { styles: answers.styles, group: answers.group, mustHaves: answers.mustHaves, budget: answers.budget },
      ),
    [items, maxDrive, answers.ferry, answers.styles, answers.group, answers.mustHaves, answers.budget],
  );
  const recommended = recommendedPlaceCount(dayCount);
  const topIds = ranked.slice(0, recommended).map((r) => r.suggestion.id);
  // picked places, in the order they were picked, still within reach
  const reachableIds = new Set(ranked.map((r) => r.suggestion.id));
  const selectedPlaces = selected
    .filter((id) => reachableIds.has(id))
    .map((id) => items.find((s) => s.id === id))
    .filter((s): s is PlaceSuggestion => !!s);
  const savedIds = new Set(data.destinations.map((d) => d.id));
  const researchSettled =
    selectedPlaces.length > 0 &&
    selectedPlaces.every((s) => research[s.id]?.status === 'done' || research[s.id]?.status === 'error');
  const researchAllDone = selectedPlaces.length > 0 && selectedPlaces.every((s) => research[s.id]?.status === 'done');

  // ---- research queue: one Gemini call at a time, spaced out
  const researchRunning = useRef(false);
  const lastResearchAt = useRef(0);
  const [researchTick, setResearchTick] = useState(0);
  useEffect(() => {
    if (researchRunning.current) return;
    const next = selectedPlaces.find((s) => research[s.id]?.status === 'queued');
    if (!next) return;
    researchRunning.current = true;
    void (async () => {
      const wait = lastResearchAt.current + RESEARCH_SPACING_MS - Date.now();
      if (lastResearchAt.current > 0 && wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
      if (!alive.current) return;
      setResearch((current) => ({ ...current, [next.id]: { status: 'loading' } }));
      lastResearchAt.current = Date.now();
      try {
        const result = await fetchAiFindingsForDestination(suggestionAsDestination(next), lang);
        if (!alive.current) return;
        if (result.status === 'error') {
          setResearch((current) => ({ ...current, [next.id]: { status: 'error', error: result.error ?? '' } }));
        } else {
          setResearch((current) => ({ ...current, [next.id]: { status: 'done', result } }));
          setPicks((current) => (current[next.id] ? current : { ...current, [next.id]: defaultPicks(result) }));
        }
      } catch (err) {
        if (!alive.current) return;
        const message =
          err instanceof GeminiApiKeyMissingError ? t('ai.unavailable') : err instanceof Error ? err.message : '';
        setResearch((current) => ({ ...current, [next.id]: { status: 'error', error: message } }));
      } finally {
        researchRunning.current = false;
        // look for the next queued place
        setResearchTick((n) => n + 1);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [research, researchTick, selected]);

  /** queue the picked places that haven't been researched yet (failed ones get another go) */
  function queueResearch() {
    setResearch((current) => {
      const next = { ...current };
      for (const place of selectedPlaces) {
        const status = current[place.id]?.status;
        if (status !== 'done' && status !== 'loading') next[place.id] = { status: 'queued' };
      }
      return next;
    });
  }

  // ---- the plan of the last step
  const plan = useMemo(
    () =>
      step === 'plan' && dayCount > 0
        ? planDays(
            selectedPlaces.map((s) => ({ id: s.id, location: s.location, driveMinutes: s.driveMinutes })),
            dayCount,
            home.location,
            null,
            0,
          )
        : [],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [step, dayCount, selected, items, home.location],
  );

  const found = countFindings(selectedPlaces.flatMap((s) => keptFindings(research[s.id], picks[s.id])));

  // ---- navigation
  useEffect(() => {
    scrollRef.current?.scrollTo({ top: 0 });
  }, [step, reviewIndex]);

  function goTo(next: WizardStep) {
    if (next === 'research') queueResearch();
    if (next === 'review') setReviewIndex(0);
    setStep(next);
  }

  function goBack(): boolean {
    if (step === 'dates') return false;
    if (step === 'review' && reviewIndex > 0) {
      setReviewIndex(reviewIndex - 1);
    } else if (step === 'review') {
      // research is automatic; going back skips straight to the picks
      setStep('places');
    } else {
      setStep(STEPS[STEPS.indexOf(step) - 1]);
    }
    return true;
  }

  useEffect(() => {
    if (!backHandlerRef) return;
    backHandlerRef.current = goBack;
    return () => {
      backHandlerRef.current = null;
    };
  });

  function finish() {
    if (!answers.start || !endDate || !answers.group) return;
    const preferences: TripPreferences = {
      maxDriveMinutes: maxDrive,
      ferry: answers.ferry,
      group: answers.group,
      styles: answers.styles,
      budget: answers.budget,
      mustHaves: answers.mustHaves,
    };
    onFinish({
      name: t('wizard.tripName', {
        g: t(`wizard.group.${answers.group}`),
        m: formatDate(answers.start, lang, { month: 'short', year: 'numeric' }),
      }),
      startDate: answers.start,
      endDate,
      plan,
      places: selectedPlaces.map((s) => toPlannedPlace(s, research[s.id], picks[s.id])),
      preferences,
      suggestions: items,
    });
  }

  const lastReviewPlace = reviewIndex >= selectedPlaces.length - 1;
  const valid: Record<WizardStep, boolean> = {
    dates: !!answers.start,
    drive: answers.drive !== undefined,
    group: !!answers.group,
    style: true,
    extras: true,
    places: selectedPlaces.length > 0,
    research: researchSettled,
    review: selectedPlaces.length > 0,
    plan: true,
  };

  function next() {
    if (!valid[step]) return;
    if (step === 'review') {
      if (!lastReviewPlace) setReviewIndex(reviewIndex + 1);
      else goTo('plan');
      return;
    }
    if (step === 'plan') {
      finish();
      return;
    }
    goTo(STEPS[STEPS.indexOf(step) + 1]);
  }

  const homeShort = home.name.replace(/^.*—\s*/, '');
  const titles: Record<WizardStep, string> = {
    dates: t('wizard.q.dates'),
    drive: t('wizard.q.drive'),
    group: t('wizard.q.group'),
    style: t('wizard.q.style'),
    extras: t('wizard.q.extras'),
    places: t('wizard.q.places'),
    research: researchAllDone ? t('wizard.q.researchDone') : t('wizard.q.research'),
    review: t('wizard.q.review'),
    plan: t('wizard.q.plan', { n: dayCount }),
  };
  const subtitles: Record<WizardStep, string> = {
    dates: t('wizard.s.dates'),
    drive: t('wizard.s.drive', { home: homeShort }),
    group: t('wizard.s.group'),
    style: t('wizard.s.style'),
    extras: t('wizard.s.extras'),
    places: t('wizard.s.places', { n: ranked.length }),
    research: t('wizard.s.research'),
    review: t('wizard.s.review'),
    plan: t('wizard.s.plan', { home: homeShort }),
  };
  const primaryLabel: Record<WizardStep, string> = {
    dates: t('wizard.continue'),
    drive: t('wizard.continue'),
    group: t('wizard.continue'),
    style: t('wizard.continue'),
    extras: t('wizard.continue'),
    places: t('wizard.find', { k: selectedPlaces.length }),
    research: t('wizard.review'),
    review: lastReviewPlace ? t('wizard.save', { k: selectedPlaces.length }) : t('wizard.nextPlace'),
    plan: t('wizard.openTrip'),
  };
  const primaryIcon = step === 'plan' ? 'check' : step === 'places' ? 'auto_awesome' : 'arrow_forward';
  const optional = step === 'style' || step === 'extras';
  const showPrevious = step === 'review' && reviewIndex > 0;
  const segment = segmentOf(step);

  const reviewPlace = selectedPlaces[Math.min(reviewIndex, selectedPlaces.length - 1)];
  const reviewState = reviewPlace ? research[reviewPlace.id] : undefined;
  const reviewResult = reviewState?.status === 'done' ? reviewState.result : null;

  const wizard = (
    <div
      className={`vm-wizard vm-wizard-${variant}`}
      role="dialog"
      aria-modal="true"
      aria-labelledby="vm-wizard-title"
    >
      <div className="vm-wizard-bar">
        <button
          type="button"
          className="vm-circle-btn vm-wizard-bar-btn"
          aria-label={t('detail.back')}
          style={{ visibility: step === 'dates' ? 'hidden' : 'visible' }}
          onClick={goBack}
        >
          <Icon name="arrow_back" size={24} />
        </button>
        <span className="vm-wizard-step-label">
          {step !== 'plan' && `${t('wizard.step', { n: segment + 1, m: SEGMENTS.length })} · ${t(SEGMENTS[segment])}`}
        </span>
        <button type="button" className="vm-circle-btn vm-wizard-bar-btn" aria-label={t('detail.close')} onClick={onClose}>
          <Icon name="close" size={24} />
        </button>
      </div>
      <div className="vm-wizard-progress" aria-hidden="true">
        {SEGMENTS.map((key, i) => (
          <span
            key={key}
            className={`vm-wizard-seg${i < segment ? ' vm-wizard-seg-done' : i === segment ? ' vm-wizard-seg-current' : ''}`}
          />
        ))}
      </div>

      <div className="vm-wizard-scroll" ref={scrollRef}>
        <div className="vm-wizard-content">
          <div className="vm-wizard-heading">
            <h1 id="vm-wizard-title" className="vm-wizard-title">
              {titles[step]}
            </h1>
            <p className="vm-wizard-sub">{subtitles[step]}</p>
          </div>

          {step === 'dates' && (
            <DatesStep
              home={home}
              range={range}
              month={month}
              dayCount={dayCount}
              onMonthChange={setMonth}
              onPick={(date) => {
                const picked = pickRangeDate(range, date);
                update({ start: picked.start, end: picked.end });
              }}
              onLength={(days) => {
                // from the picked start, else from today (or the shown month if it's later)
                const today = todayIso();
                const base = answers.start ?? (month <= today.slice(0, 7) ? today : `${month}-01`);
                update({ start: base, end: addDays(base, days - 1) });
                setMonth(base.slice(0, 7));
              }}
              onChangeHome={onChangeHome}
            />
          )}
          {step === 'drive' && (
            <DriveStep
              drive={answers.drive}
              ferry={answers.ferry}
              countFor={(minutes) =>
                catalog.status === 'ready'
                  ? catalog.items.filter((s) => isReachable(s, minutes, answers.ferry)).length
                  : catalog.status === 'loading'
                    ? null
                    : undefined
              }
              onDrive={(minutes) => update({ drive: minutes })}
              onToggleFerry={() => update({ ferry: !answers.ferry })}
            />
          )}
          {step === 'group' && <GroupStep group={answers.group} onGroup={(group) => update({ group })} />}
          {step === 'style' && (
            <StyleStep
              styles={answers.styles}
              onToggle={(style) =>
                update({
                  styles: answers.styles.includes(style)
                    ? answers.styles.filter((s) => s !== style)
                    : [...answers.styles, style],
                })
              }
            />
          )}
          {step === 'extras' && (
            <ExtrasStep
              budget={answers.budget}
              mustHaves={answers.mustHaves}
              // tapping the chosen budget again clears it
              onBudget={(level) => update({ budget: answers.budget === level ? null : level })}
              onToggleMust={(must) =>
                update({
                  mustHaves: answers.mustHaves.includes(must)
                    ? answers.mustHaves.filter((m) => m !== must)
                    : [...answers.mustHaves, must],
                })
              }
            />
          )}
          {step === 'places' && (
            <PlacesStep
              home={home}
              status={catalog.status}
              error={catalog.status === 'error' ? catalog.error : null}
              ranked={ranked}
              selected={selected}
              savedIds={savedIds}
              topIds={topIds}
              thumbnails={thumbnails}
              dayCount={dayCount}
              recommended={recommended}
              onToggle={(id) =>
                setSelected((current) => (current.includes(id) ? current.filter((x) => x !== id) : [...current, id]))
              }
              onPickTop={() => setSelected(topIds)}
              onRetry={() => void loadCatalog()}
            />
          )}
          {step === 'research' && (
            <ResearchStep
              places={selectedPlaces}
              research={research}
              onRetry={(id) => setResearch((current) => ({ ...current, [id]: { status: 'queued' } }))}
            />
          )}
          {step === 'review' && reviewPlace && (
            <ReviewStep
              place={reviewPlace}
              index={Math.min(reviewIndex, selectedPlaces.length - 1)}
              total={selectedPlaces.length}
              result={reviewResult}
              picks={picks[reviewPlace.id] ?? NO_PICKS}
              thumbnail={thumbnails[reviewPlace.id]}
              onPicks={(next) => setPicks((current) => ({ ...current, [reviewPlace.id]: next }))}
            />
          )}
          {step === 'plan' && answers.start && (
            <PlanStep
              plan={plan}
              places={new Map(selectedPlaces.map((s) => [s.id, s]))}
              startDate={answers.start}
              savedCount={selectedPlaces.length}
              found={found}
            />
          )}
        </div>
      </div>

      <div className="vm-wizard-footer">
        <div className="vm-wizard-footer-inner">
          {optional && (
            <button type="button" className="vm-text-btn vm-wizard-secondary" onClick={() => goTo(STEPS[STEPS.indexOf(step) + 1])}>
              {t('wizard.skip')}
            </button>
          )}
          {showPrevious && (
            <button type="button" className="vm-text-btn vm-wizard-secondary" onClick={goBack}>
              {t('wizard.prevPlace')}
            </button>
          )}
          <button
            type="button"
            className={`vm-wizard-primary${step === 'plan' ? ' vm-wizard-primary-finish' : ''}`}
            disabled={!valid[step]}
            onClick={next}
          >
            <span>{primaryLabel[step]}</span>
            <Icon name={primaryIcon} size={20} />
          </button>
        </div>
      </div>
    </div>
  );

  if (variant === 'mobile') return wizard;
  // desktop: a dialog over a scrim; Esc deliberately doesn't close it, to keep the answers
  return <div className="vm-wizard-scrim">{wizard}</div>;
}
