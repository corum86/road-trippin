import { useRef, useState } from 'react';
import { useMapDataStore } from '../../store/mapDataStore';

export function DataImportExportControls() {
  const data = useMapDataStore((s) => s.data);
  const replaceAllData = useMapDataStore((s) => s.replaceAllData);
  const resetToBundledDefaults = useMapDataStore((s) => s.resetToBundledDefaults);
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
          !json.mainLocation ||
          !Array.isArray(json.destinations)
        ) {
          throw new Error('File does not match the expected vacation data format.');
        }
        replaceAllData(json);
        setImportError(null);
      } catch (err) {
        setImportError(err instanceof Error ? err.message : 'Failed to import file.');
      }
    };
    reader.onerror = () => setImportError('Failed to read file.');
    reader.readAsText(file);
  }

  function handleReset() {
    if (window.confirm('Reset all data to the bundled defaults? Your current edits in this browser will be lost unless exported first.')) {
      resetToBundledDefaults();
    }
  }

  return (
    <div className="vm-data-controls">
      <button type="button" onClick={handleExport} title="Download current data as a JSON file">
        Export data
      </button>
      <button type="button" onClick={handleImportClick} title="Load data from a JSON file">
        Import data
      </button>
      <input
        ref={fileInputRef}
        type="file"
        accept="application/json"
        style={{ display: 'none' }}
        onChange={handleFileChange}
      />
      <button type="button" className="vm-btn-secondary" onClick={handleReset} title="Reset to bundled defaults">
        Reset
      </button>
      {importError && <span className="vm-import-error">{importError}</span>}
      <span className="vm-data-hint">Edits save to this browser only — export to back up.</span>
    </div>
  );
}
