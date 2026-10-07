import { useEffect, useState } from 'react';
import { useMapDataStore } from '../../store/mapDataStore';
import { domainOf, withFindings } from '../../services/aiFindings';
import type { AiFinding, DestinationAiResult } from '../../types/ai';
import { useI18n } from '../../i18n/context';
import { Icon } from '../ui/Icon';

interface AiResearchStepperProps {
  results: DestinationAiResult[];
  onClose: () => void;
}

export function AiResearchStepper({ results, onClose }: AiResearchStepperProps) {
  const { t } = useI18n();
  const updateDestination = useMapDataStore((s) => s.updateDestination);
  const [localResults, setLocalResults] = useState(results);
  const [stepIndex, setStepIndex] = useState(0);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  const step = localResults[stepIndex];
  const count = localResults.length;
  if (!step || count === 0) return null;

  /** save the card to its destination: the text, the photo and the link together */
  function addFinding(finding: AiFinding) {
    const dest = useMapDataStore.getState().data?.destinations.find((d) => d.id === step.destinationId);
    if (!dest) return;
    updateDestination(dest.id, withFindings(dest, [finding]));
    setLocalResults((prev) =>
      prev.map((r, i) =>
        i === stepIndex
          ? { ...r, findings: r.findings.map((f) => (f.id === finding.id ? { ...f, added: true } : f)) }
          : r,
      ),
    );
  }

  return (
    <div className="vm-stepper-backdrop" onClick={onClose}>
      <div className="vm-stepper-content" onClick={(e) => e.stopPropagation()}>
        <div className="vm-stepper-header">
          <div>
            <span className="vm-stepper-counter">
              {t('stepper.counter', { current: stepIndex + 1, total: count })}
            </span>
            <h2>{step.destinationName}</h2>
            {step.grounded && (
              <span className="vm-grounded-chip">
                <Icon name="travel_explore" size={16} />
                {t('ai.grounded')}
              </span>
            )}
          </div>
          <button type="button" className="vm-stepper-close" onClick={onClose} aria-label={t('detail.close')}>
            ✕
          </button>
        </div>

        <div className="vm-stepper-body">
          {step.status === 'error' ? (
            <p className="vm-import-error">{t('stepper.error', { error: step.error ?? '' })}</p>
          ) : step.findings.length === 0 ? (
            <p className="vm-stepper-empty">{t('stepper.nothing')}</p>
          ) : (
            <div className="vm-stepper-cards">
              {step.findings.map((finding) => (
                <div className="vm-stepper-card" key={finding.id}>
                  {finding.photo && <img src={finding.photo.imageUrl} alt={finding.photo.sourceTitle} loading="lazy" />}
                  <div className="vm-stepper-card-body">
                    {finding.name && <h3>{finding.name}</h3>}
                    {finding.text && <p>{finding.text}</p>}
                    {finding.link && (
                      <a href={finding.link.url} target="_blank" rel="noopener noreferrer" title={finding.link.label}>
                        {domainOf(finding.link.url)} ↗
                      </a>
                    )}
                  </div>
                  <button
                    type="button"
                    className="vm-ai-add-btn"
                    disabled={finding.added}
                    onClick={() => addFinding(finding)}
                    aria-label={t('stepper.addFinding', { name: finding.name || finding.text })}
                  >
                    {finding.added ? t('stepper.added') : t('stepper.add')}
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="vm-stepper-nav">
          <button
            type="button"
            className="vm-btn-secondary"
            disabled={stepIndex === 0}
            onClick={() => setStepIndex((i) => Math.max(0, i - 1))}
          >
            {t('stepper.back')}
          </button>
          <button
            type="button"
            className="vm-btn-secondary"
            disabled={stepIndex === count - 1}
            onClick={() => setStepIndex((i) => Math.min(count - 1, i + 1))}
          >
            {t('stepper.next')}
          </button>
        </div>
      </div>
    </div>
  );
}
