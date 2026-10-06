import { useRef, useState } from 'react';
import { useMapDataStore } from '../../store/mapDataStore';
import { useI18n } from '../../i18n/context';

interface DataImportExportControlsProps {
  /** the shell asks for confirmation, then clears the app */
  onReset: () => void;
}

export function DataImportExportControls({ onReset }: DataImportExportControlsProps) {
  const { t } = useI18n();
  const data = useMapDataStore((s) => s.data);
  const replaceAllData = useMapDataStore((s) => s.replaceAllData);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [importError, setImportError] = useState<string | null>(null);

  function handleExport() {
    if (!data) return;
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `vacation-data-${Date.now()}.json`;
    link.click();
    URL.revokeObjectURL(url);
  }

  function handleImportClick() {
    setImportError(null);
    fileInputRef.current?.click();
  }

  function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file) return;

    const reader = new FileReader();
    reader.onload = () => {
      try {
        const json = JSON.parse(String(reader.result));
        if (
          typeof json !== 'object' ||
          json === null ||
          typeof json.version !== 'number' ||
          typeof json.mainLocation !== 'object' ||
          !Array.isArray(json.destinations)
        ) {
          throw new Error(t('data.invalidFormat'));
        }
        replaceAllData(json);
        setImportError(null);
      } catch (err) {
        setImportError(err instanceof Error ? err.message : t('data.importFailed'));
      }
    };
    reader.onerror = () => setImportError(t('data.readFailed'));
    reader.readAsText(file);
  }

  // laid out as a row of Settings option buttons
  return (
    <div className="vm-data-controls">
      <div className="vm-options">
        <button type="button" className="vm-option" onClick={handleExport} title={t('data.exportTitle')}>
          {t('data.export')}
        </button>
        <button type="button" className="vm-option" onClick={handleImportClick} title={t('data.importTitle')}>
          {t('data.import')}
        </button>
        <button type="button" className="vm-option vm-option-danger" onClick={onReset} title={t('data.resetTitle')}>
          {t('data.reset')}
        </button>
      </div>
      <input
        ref={fileInputRef}
        type="file"
        accept="application/json"
        style={{ display: 'none' }}
        onChange={handleFileChange}
      />
      {importError && (
        <div className="vm-form-error" role="alert">
          {importError}
        </div>
      )}
    </div>
  );
}
