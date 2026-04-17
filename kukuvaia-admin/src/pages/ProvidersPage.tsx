import { useEffect, useState } from 'react';
import { providers, type Provider } from '../api/client';

type TestStatus = 'idle' | 'testing' | 'connected' | 'failed';

export function ProvidersPage() {
  const [list, setList] = useState<Provider[]>([]);
  const [name, setName] = useState('');
  const [type, setType] = useState('smartgate');
  const [baseUrl, setBaseUrl] = useState('');
  const [apiKeyRef, setApiKeyRef] = useState('');
  const [priority, setPriority] = useState(0);
  const [enabled, setEnabled] = useState(true);
  const [error, setError] = useState('');
  const [testStatus, setTestStatus] = useState<TestStatus>('idle');
  const [testInfo, setTestInfo] = useState('');
  const [saving, setSaving] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);

  const load = () => { providers.list().then(d => setList(d || [])); };
  useEffect(load, []);

  const resetForm = () => {
    setName(''); setType('smartgate'); setBaseUrl(''); setApiKeyRef('');
    setPriority(0); setEnabled(true);
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
    setTestStatus('idle'); setTestInfo('');
    setError('');
  };

  const handleTest = async () => {
    if (!baseUrl || !apiKeyRef) {
      setError('Base URL and API Key Ref are required to test.');
      return;
    }
    setError('');
    setTestStatus('testing');
    setTestInfo('');
    const result = await providers.testConnectionRaw({ baseUrl, apiKeyRef });
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
    setError('');
    setSaving(true);
    const result = await providers.create({ name, type, baseUrl, apiKeyRef, priority });
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
    setError('');
    setSaving(true);
    const data: Parameters<typeof providers.update>[1] = { name, type, baseUrl, enabled, priority };
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
