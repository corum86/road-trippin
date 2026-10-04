import { useEffect, useState } from 'react';
import { v4 as uuidv4 } from 'uuid';
import { useMapDataStore } from '../../store/mapDataStore';
import type { AiImageFinding, AiLinkFinding, AiTextFinding, DestinationAiResult } from '../../types/ai';
import { useI18n } from '../../i18n/context';
import type { TranslationKey } from '../../i18n/translations';

interface AiResearchStepperProps {
  results: DestinationAiResult[];
  onClose: () => void;
}

type TabId = 'images' | 'text' | 'links';

const TABS: Array<{ id: TabId; labelKey: TranslationKey }> = [
  { id: 'images', labelKey: 'stepper.tabImages' },
  { id: 'text', labelKey: 'stepper.tabText' },
  { id: 'links', labelKey: 'stepper.tabLinks' },
];

export function AiResearchStepper({ results, onClose }: AiResearchStepperProps) {
  const { t } = useI18n();
  const updateDestination = useMapDataStore((s) => s.updateDestination);
  const [localResults, setLocalResults] = useState(results);
  const [stepIndex, setStepIndex] = useState(0);
  const [activeTab, setActiveTab] = useState<TabId>('images');

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

  function markAdded(findingId: string) {
    setLocalResults((prev) =>
      prev.map((r, i) => {
        if (i !== stepIndex) return r;
        return {
          ...r,
          images: r.images.map((f) => (f.id === findingId ? { ...f, added: true } : f)),
          texts: r.texts.map((f) => (f.id === findingId ? { ...f, added: true } : f)),
          links: r.links.map((f) => (f.id === findingId ? { ...f, added: true } : f)),
        };
      }),
    );
  }

  function addText(finding: AiTextFinding) {
    const dest = useMapDataStore.getState().data?.destinations.find((d) => d.id === step.destinationId);
    if (!dest) return;
    if (!dest.attractions.includes(finding.fact)) {
      updateDestination(dest.id, { attractions: [...dest.attractions, finding.fact] });
    }
    markAdded(finding.id);
  }

  function addLink(finding: AiLinkFinding) {
    const dest = useMapDataStore.getState().data?.destinations.find((d) => d.id === step.destinationId);
    if (!dest) return;
    if (!dest.links.some((l) => l.url === finding.url)) {
      updateDestination(dest.id, {
        links: [...dest.links, { id: uuidv4(), label: finding.label, url: finding.url }],
      });
    }
    markAdded(finding.id);
  }

  function addImage(finding: AiImageFinding) {
    const dest = useMapDataStore.getState().data?.destinations.find((d) => d.id === step.destinationId);
    if (!dest) return;
    if (!dest.photos.some((p) => p.url === finding.imageUrl)) {
      updateDestination(dest.id, {
        photos: [...dest.photos, { id: uuidv4(), url: finding.imageUrl, caption: finding.sourceTitle }],
      });
    }
    markAdded(finding.id);
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
          </div>
          <button type="button" className="vm-stepper-close" onClick={onClose} aria-label={t('detail.close')}>
            ✕
          </button>
        </div>

        <div className="vm-stepper-tabs">
          {TABS.map((tab) => (
            <button
              key={tab.id}
              type="button"
              className={activeTab === tab.id ? 'vm-tab vm-tab-active' : 'vm-tab'}
              onClick={() => setActiveTab(tab.id)}
            >
              {t(tab.labelKey)}
            </button>
          ))}
        </div>

        <div className="vm-stepper-body">
          {step.status === 'error' ? (
            <p className="vm-import-error">{t('stepper.error', { error: step.error ?? '' })}</p>
          ) : (
            <>
              {activeTab === 'images' &&
                (step.images.length === 0 ? (
                  <p className="vm-stepper-empty">{t('stepper.noImages')}</p>
                ) : (
                  <div className="vm-stepper-image-grid">
                    {step.images.map((finding) => (
                      <div className="vm-stepper-image-card" key={finding.id}>
                        <img src={finding.imageUrl} alt={finding.sourceTitle} loading="lazy" />
                        <div className="vm-stepper-image-caption">{finding.sourceTitle}</div>
                        <button
                          type="button"
                          className="vm-ai-add-btn"
                          disabled={finding.added}
                          onClick={() => addImage(finding)}
                          aria-label={t('stepper.addImage')}
                        >
                          {finding.added ? t('stepper.added') : t('stepper.add')}
                        </button>
                      </div>
                    ))}
                  </div>
                ))}

              {activeTab === 'text' &&
                (step.texts.length === 0 ? (
                  <p className="vm-stepper-empty">{t('stepper.noFacts')}</p>
                ) : (
                  <ul className="vm-stepper-list">
                    {step.texts.map((finding) => (
                      <li className="vm-stepper-row" key={finding.id}>
                        <span>{finding.fact}</span>
                        <button
                          type="button"
                          className="vm-ai-add-btn"
                          disabled={finding.added}
                          onClick={() => addText(finding)}
                          aria-label={t('stepper.addFact')}
                        >
                          {finding.added ? t('stepper.added') : t('stepper.add')}
                        </button>
                      </li>
                    ))}
                  </ul>
                ))}

              {activeTab === 'links' &&
                (step.links.length === 0 ? (
                  <p className="vm-stepper-empty">{t('stepper.noLinks')}</p>
                ) : (
                  <ul className="vm-stepper-list">
                    {step.links.map((finding) => (
                      <li className="vm-stepper-row" key={finding.id}>
                        <a href={finding.url} target="_blank" rel="noopener noreferrer">
                          {finding.label}
                        </a>
                        <button
                          type="button"
                          className="vm-ai-add-btn"
                          disabled={finding.added}
                          onClick={() => addLink(finding)}
                          aria-label={t('stepper.addLink')}
                        >
                          {finding.added ? t('stepper.added') : t('stepper.add')}
                        </button>
                      </li>
                    ))}
                  </ul>
                ))}
            </>
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
