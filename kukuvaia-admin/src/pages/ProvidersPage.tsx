import { useEffect, useState } from 'react';
import { providers, type Provider } from '../api/client';

type TestStatus = 'idle' | 'testing' | 'connected' | 'failed';

/**
 * Parse the Config textarea. Empty/whitespace → empty object. Returns either the
 * parsed record or a human-readable error so the form can keep the offending text
 * in place for the user to fix.
 */
function parseConfigText(text: string): { ok: true; value: Record<string, unknown> } | { ok: false; error: string } {
  const trimmed = text.trim();
  if (trimmed === '') return { ok: true, value: {} };
  try {
    const parsed: unknown = JSON.parse(trimmed);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return { ok: false, error: 'Config must be a JSON object (e.g. {"completions-path": "/chat/completions"}).' };
    }
    return { ok: true, value: parsed as Record<string, unknown> };
  } catch (e) {
    const message = e instanceof Error ? e.message : 'Invalid JSON';
    return { ok: false, error: `Invalid JSON: ${message}` };
  }
}

// GitHub validates Copilot-Integration-Id against an internal allowlist; unregistered
// IDs are rejected with HTTP 400 "unknown Copilot-Integration-Id". Unofficial clients
// (Aider, Avante.nvim, ...) reuse "vscode-chat" — using the public Copilot API from
// a server-side daemon is a GitHub AUP gray area, so interactive use only.
const COPILOT_CONFIG_HINT = '{\n  "completions-path": "/chat/completions",\n  "models-path": "/models",\n  "headers": {\n    "Copilot-Integration-Id": "vscode-chat",\n    "Editor-Version": "vscode/1.95.0",\n    "Editor-Plugin-Version": "copilot-chat/0.20.0"\n  }\n}';

export function ProvidersPage() {
  const [list, setList] = useState<Provider[]>([]);
  const [name, setName] = useState('');
  const [type, setType] = useState('smartgate');
  const [baseUrl, setBaseUrl] = useState('');
  const [apiKeyRef, setApiKeyRef] = useState('');
  const [priority, setPriority] = useState(0);
  const [enabled, setEnabled] = useState(true);
  const [configText, setConfigText] = useState('');
  const [error, setError] = useState('');
  const [testStatus, setTestStatus] = useState<TestStatus>('idle');
  const [testInfo, setTestInfo] = useState('');
  const [saving, setSaving] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);

  const load = () => { providers.list().then(d => setList(d || [])); };
  useEffect(load, []);

  const resetForm = () => {
    setName(''); setType('smartgate'); setBaseUrl(''); setApiKeyRef('');
    setPriority(0); setEnabled(true); setConfigText('');
    setTestStatus('idle'); setTestInfo('');
    setEditingId(null); setError('');
  };

  const handleEdit = (p: Provider) => {
    setEditingId(p.id);
    setName(p.name);
    setType(p.type);
    setBaseUrl(p.baseUrl);
    setApiKeyRef('');
    setPriority(p.priority);
    setEnabled(p.enabled);
    setConfigText(p.config && Object.keys(p.config).length > 0
      ? JSON.stringify(p.config, null, 2)
      : '');
    setTestStatus('idle'); setTestInfo('');
    setError('');
  };

  const handleTest = async () => {
    if (!baseUrl || !apiKeyRef) {
      setError('Base URL and API Key Ref are required to test.');
      return;
    }
    const parsed = parseConfigText(configText);
    if (!parsed.ok) {
      setError(parsed.error);
      return;
    }
    setError('');
    setTestStatus('testing');
    setTestInfo('');
    const result = await providers.testConnectionRaw({ baseUrl, apiKeyRef, config: parsed.value });
    if (!result.ok) {
      setTestStatus('failed');
      setTestInfo(result.error.message);
      return;
    }
    if (result.data.status === 'connected') {
      setTestStatus('connected');
      setTestInfo(`Connected (${result.data.latencyMs}ms)`);
    } else {
      setTestStatus('failed');
      setTestInfo(result.data.error || 'Unknown error');
    }
  };

  const handleCreate = async () => {
    if (!name || !baseUrl || !apiKeyRef) {
      setError('All fields are required.');
      return;
    }
    const parsed = parseConfigText(configText);
    if (!parsed.ok) {
      setError(parsed.error);
      return;
    }
    setError('');
    setSaving(true);
    const result = await providers.create({ name, type, baseUrl, apiKeyRef, priority, config: parsed.value });
    setSaving(false);
    if (!result.ok) {
      setError(result.error.message);
      return;
    }
    resetForm();
    load();
  };

  const handleUpdate = async () => {
    if (!editingId || !name || !baseUrl) {
      setError('Name and Base URL are required.');
      return;
    }
    const parsed = parseConfigText(configText);
    if (!parsed.ok) {
      setError(parsed.error);
      return;
    }
    setError('');
    setSaving(true);
    const data: Parameters<typeof providers.update>[1] = { name, type, baseUrl, enabled, priority, config: parsed.value };
    if (apiKeyRef) data.apiKeyRef = apiKeyRef;
    const result = await providers.update(editingId, data);
    setSaving(false);
    if (!result.ok) {
      setError(result.error.message);
      return;
    }
    resetForm();
    load();
  };

  const handleSync = async (id: string) => {
    await providers.syncModels(id);
    load();
  };

  const handleDelete = async (id: string) => {
    const result = await providers.delete(id);
    if (!result.ok) {
      alert(result.error.message);
      return;
    }
    if (editingId === id) resetForm();
    load();
  };

  const handleTestExisting = async (id: string) => {
    const result = await providers.testConnection(id);
    if (result) alert(`Status: ${result.status}${result.latencyMs ? ` (${result.latencyMs}ms)` : ''}${result.error ? `\nError: ${result.error}` : ''}`);
  };

  const resetTestOnChange = () => {
    if (testStatus !== 'idle') {
      setTestStatus('idle');
      setTestInfo('');
    }
  };

  return (
    <>
      <h1>Providers</h1>
      <div className="card">
        {list.length === 0 ? (
          <p className="empty">No providers registered</p>
        ) : (
          <table>
            <thead><tr><th>Name</th><th>Type</th><th>Base URL</th><th>Models</th><th>Priority</th><th>Status</th><th>Actions</th></tr></thead>
            <tbody>
              {list.map(p => (
                <tr key={p.id} className={editingId === p.id ? 'row-editing' : ''}>
                  <td><strong>{p.name}</strong></td>
                  <td>{p.type}</td>
                  <td style={{ maxWidth: 250, overflow: 'hidden', textOverflow: 'ellipsis' }}>{p.baseUrl}</td>
                  <td>{p.modelCount}</td>
                  <td>{p.priority}</td>
                  <td><span className={`badge ${p.enabled ? 'badge-ok' : 'badge-err'}`}>{p.enabled ? 'active' : 'off'}</span></td>
                  <td style={{ display: 'flex', gap: 4 }}>
                    <button className="sm" onClick={() => handleEdit(p)}>edit</button>
                    <button className="sm" onClick={() => handleSync(p.id)}>sync</button>
                    <button className="sm" onClick={() => handleTestExisting(p.id)}>test</button>
                    <button className="sm danger" onClick={() => handleDelete(p.id)}>delete</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="card">
        <h2>{editingId ? 'Edit Provider' : 'Add Provider'}</h2>
        {error && <p className="form-error">{error}</p>}
        <div className="form-row">
          <div><label>Name</label><input value={name} onChange={e => setName(e.target.value)} placeholder="smartgate" /></div>
          <div><label>Type</label>
            <select value={type} onChange={e => setType(e.target.value)}>
              <option>smartgate</option><option>openrouter</option><option>openai</option>
              <option>anthropic</option><option>ollama</option><option>custom</option>
            </select>
          </div>
        </div>
        <div className="form-row">
          <div><label>Base URL</label><input value={baseUrl} onChange={e => { setBaseUrl(e.target.value); resetTestOnChange(); }} placeholder="https://llm.example.com" /></div>
        </div>
        <div className="form-row">
          <div><label>{editingId ? 'API Key (leave empty to keep current)' : 'API Key (env var name or token)'}</label><input value={apiKeyRef} onChange={e => { setApiKeyRef(e.target.value); resetTestOnChange(); }} placeholder={editingId ? '(unchanged)' : 'SMARTGATE_API_KEY or JWT token'} /></div>
        </div>
        <div className="form-row">
          <div style={{ flex: 1 }}>
            <label style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span>Config (JSON, optional)</span>
              <button
                type="button"
                className="sm secondary"
                onClick={() => { setConfigText(COPILOT_CONFIG_HINT); resetTestOnChange(); }}
                title="Insert GitHub Copilot template (paths + integration headers)"
              >
                Copilot template
              </button>
            </label>
            <textarea
              value={configText}
              onChange={e => { setConfigText(e.target.value); resetTestOnChange(); }}
              placeholder='{"completions-path": "/chat/completions", "models-path": "/models", "headers": {...}}'
              rows={6}
              spellCheck={false}
              style={{ fontFamily: 'monospace', fontSize: '0.85rem', width: '100%' }}
            />
            <small style={{ color: 'var(--muted, #888)' }}>
              Overrides for providers that diverge from the OpenAI surface. Keys: <code>completions-path</code>, <code>models-path</code>, <code>headers</code>.
            </small>
          </div>
        </div>
        <div className="form-row">
          <div><label>Priority</label><input type="number" value={priority} onChange={e => setPriority(Number(e.target.value))} min={0} /></div>
          {editingId && (
            <div><label>Enabled</label>
              <select value={enabled ? 'true' : 'false'} onChange={e => setEnabled(e.target.value === 'true')}>
                <option value="true">active</option>
                <option value="false">off</option>
              </select>
            </div>
          )}
          <div><label>&nbsp;</label>
            <div style={{ display: 'flex', gap: 6 }}>
              <button onClick={handleTest} disabled={testStatus === 'testing'}>
                {testStatus === 'testing' ? 'Testing...' : 'Test'}
              </button>
              {editingId ? (
                <>
                  <button onClick={handleUpdate} disabled={saving}>
                    {saving ? 'Saving...' : 'Save Changes'}
                  </button>
                  <button className="secondary" onClick={resetForm}>Cancel</button>
                </>
              ) : (
                <button onClick={handleCreate} disabled={saving}>
                  {saving ? 'Saving...' : 'Add Provider'}
                </button>
              )}
            </div>
          </div>
        </div>
        {testInfo && (
          <p className={testStatus === 'connected' ? 'form-ok' : 'form-error'}>{testInfo}</p>
        )}
      </div>
    </>
  );
}
