import { useState } from 'react';
import { useCloudSyncStore, type ConnectResult, type SyncStatus } from '../../store/cloudSync';
import { useI18n } from '../../i18n/context';
import type { TranslationKey } from '../../i18n/translations';

// 'unavailable' has no line: Settings leaves this card out then
const STATUS_KEYS: Record<Exclude<SyncStatus, 'unavailable'>, TranslationKey> = {
  connecting: 'sync.status.connecting',
  saving: 'sync.status.saving',
  synced: 'sync.status.synced',
  offline: 'sync.status.offline',
  tooLarge: 'sync.status.tooLarge',
  outdated: 'sync.status.outdated',
};

const CONNECT_ERROR_KEYS: Record<Exclude<ConnectResult, 'ok'>, TranslationKey> = {
  invalid: 'sync.invalidCode',
  notFound: 'sync.notFound',
  outdated: 'sync.status.outdated',
  failed: 'sync.connectFailed',
};

const COPIED_MS = 2000;

/** Cloud save status, this device's sync code, and switching to another device's code. */
export function CloudSyncControls() {
  const { t } = useI18n();
  const status = useCloudSyncStore((s) => s.status);
  const syncCode = useCloudSyncStore((s) => s.syncCode);
  const connect = useCloudSyncStore((s) => s.connect);
  const [code, setCode] = useState('');
  const [copied, setCopied] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [connectError, setConnectError] = useState<TranslationKey | null>(null);

  if (status === 'unavailable') return null;

  const tone = status === 'synced' ? 'ok' : status === 'connecting' || status === 'saving' ? 'busy' : 'warn';

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(syncCode);
      setCopied(true);
      window.setTimeout(() => setCopied(false), COPIED_MS);
    } catch {
      // clipboard blocked: the code is on screen to select by hand
    }
  }

  async function handleConnect(e: React.FormEvent) {
    e.preventDefault();
    setConnectError(null);
    if (!window.confirm(t('sync.confirmConnect'))) return;
    setConnecting(true);
    const result = await connect(code);
    setConnecting(false);
    if (result === 'ok') setCode('');
    else setConnectError(CONNECT_ERROR_KEYS[result]);
  }

  return (
    <div className="vm-card vm-sync-card">
      <div className={`vm-sync-status vm-sync-status-${tone}`} role="status">
        <span className="vm-sync-dot" aria-hidden="true" />
        {t(STATUS_KEYS[status])}
      </div>
      <div className="vm-option-group">
        <span className="vm-option-label">{t('sync.code')}</span>
        <div className="vm-sync-row">
          <span className="vm-mono vm-sync-code">{syncCode}</span>
          <button type="button" className="vm-option" onClick={handleCopy}>
            {copied ? t('sync.copied') : t('sync.copy')}
          </button>
        </div>
      </div>
      <form className="vm-option-group" onSubmit={handleConnect}>
        <label className="vm-option-label" htmlFor="vm-sync-connect">
          {t('sync.connectLabel')}
        </label>
        <div className="vm-sync-row">
          <input
            id="vm-sync-connect"
            className="vm-input vm-input-sm vm-mono"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder={t('sync.connectPlaceholder')}
            autoComplete="off"
            autoCapitalize="none"
            spellCheck={false}
          />
          <button type="submit" className="vm-option" disabled={connecting || !code.trim()}>
            {t('sync.connect')}
          </button>
        </div>
      </form>
      {connectError && (
        <div className="vm-form-error" role="alert">
          {t(connectError)}
        </div>
      )}
    </div>
  );
}
