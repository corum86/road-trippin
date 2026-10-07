import { useState } from 'react';
import { useMapDataStore } from '../../store/mapDataStore';
import { fetchAiFindingsForAllDestinations } from '../../services/geminiService';
import type { DestinationAiResult } from '../../types/ai';
import { AiResearchStepper } from '../panels/AiResearchStepper';
import { useI18n } from '../../i18n/context';
import { Icon } from '../ui/Icon';

interface AiSearchTriggerProps {
  onError: (message: string) => void;
  /**
   * Takes the findings instead of the review opening here: for a shell that
   * shows the review as one of its own layers (mobile, for the back gesture).
   */
  onResults?: (results: DestinationAiResult[]) => void;
}

/** Icon button that researches every destination with Gemini, then opens the review stepper. */
export function AiSearchTrigger({ onError, onResults }: AiSearchTriggerProps) {
  const { t, lang } = useI18n();
  const data = useMapDataStore((s) => s.data);
  const [isSearching, setIsSearching] = useState(false);
  const [progress, setProgress] = useState<{ completed: number; total: number } | null>(null);
  const [results, setResults] = useState<DestinationAiResult[] | null>(null);

  const destinations = data?.destinations ?? [];

  async function handleSearch() {
    if (!import.meta.env.VITE_GEMINI_API_KEY) {
      onError(t('ai.unavailable'));
      return;
    }

    setIsSearching(true);
    setProgress({ completed: 0, total: destinations.length });
    try {
      // Destinations are researched one at a time (not in parallel) with
      // spacing between requests to stay under the Gemini free-tier rate
      // limit — see geminiService.fetchAiFindingsForAllDestinations.
      const found = await fetchAiFindingsForAllDestinations(
        destinations,
        (completed, total) => setProgress({ completed, total }),
        lang,
      );
      if (onResults) onResults(found);
      else setResults(found);
    } catch (err) {
      onError(err instanceof Error ? err.message : t('ai.failed'));
    } finally {
      setIsSearching(false);
      setProgress(null);
    }
  }

  const label = isSearching
    ? progress
      ? t('ai.searchingProgress', { completed: progress.completed, total: progress.total })
      : t('ai.searching')
    : t('ai.search');

  return (
    <>
      <button
        type="button"
        className="vm-ai-search-btn"
        onClick={handleSearch}
        disabled={isSearching || destinations.length === 0}
        title={destinations.length === 0 ? t('ai.addDestinationFirst') : t('ai.researchAll')}
        aria-label={label}
      >
        <Icon name="auto_awesome" size={20} />
        {isSearching && progress && (
          <span>
            {progress.completed}/{progress.total}
          </span>
        )}
      </button>
      {results && <AiResearchStepper results={results} onClose={() => setResults(null)} />}
    </>
  );
}
