import { useEffect, useState } from 'react';
import { models, providers, type Model, type Provider } from '../api/client';

type TestStatus = 'idle' | 'testing' | 'ok' | 'needs-thinking' | 'thinking-ok' | 'failed';
type EffortLevel = 'low' | 'medium' | 'high';

export function ModelsPage() {
  const [list, setList] = useState<Model[]>([]);
  const [providerList, setProviderList] = useState<Provider[]>([]);
  const [providerId, setProviderId] = useState('');
  const [modelId, setModelId] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [tier, setTier] = useState('standard');
  const [maxTokens, setMaxTokens] = useState(4096);
  const [contextWindow, setContextWindow] = useState<number | ''>('');
  const [modelEnabled, setModelEnabled] = useState(true);
  const [thinking, setThinking] = useState(false);
  const [reasoningEffort, setReasoningEffort] = useState<EffortLevel>('medium');
  const [error, setError] = useState('');
  const [testStatus, setTestStatus] = useState<TestStatus>('idle');
  const [testInfo, setTestInfo] = useState('');
  const [saving, setSaving] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);

  const load = () => {
    models.list().then(d => setList(d || []));
    providers.list().then(d => { setProviderList(d || []); if (d?.length && !providerId) setProviderId(d[0].id); });
  };
  useEffect(load, []);

  const resetForm = () => {
    setModelId(''); setDisplayName(''); setTier('standard');
    setMaxTokens(4096); setContextWindow(''); setModelEnabled(true);
    setThinking(false); setReasoningEffort('medium');
    setTestStatus('idle'); setTestInfo('');
    setEditingId(null); setError('');
    if (providerList.length) setProviderId(providerList[0].id);
  };

  const handleEdit = (m: Model) => {
    setEditingId(m.id);
    setProviderId(m.providerId);
    setModelId(m.modelId);
    setDisplayName(m.displayName || '');
    setTier(m.tier);
    setMaxTokens(m.maxTokens);
    setContextWindow(m.contextWindow ?? '');
    setModelEnabled(m.enabled);
    setThinking(m.config?.thinking === true);
    const eff = m.config?.reasoning_effort;
    setReasoningEffort(isEffort(eff) ? eff : 'medium');
    setTestStatus('idle'); setTestInfo('');
    setError('');
  };

  const buildConfig = (): Record<string, unknown> => {
    if (!thinking) return {};
    return { thinking: true, reasoning_effort: reasoningEffort };
  };

  const runTest = async (withThinking: boolean) => {
    setError('');
    setTestStatus('testing');
    setTestInfo('');
    const config = withThinking
      ? { thinking: true, reasoning_effort: reasoningEffort }
      : undefined;
    const result = await models.test({ providerId, modelId, config });
    if (!result.ok) {
      setTestStatus('failed');
      setTestInfo(result.error.message);
      return;
    }
    const data = result.data;
    if (data.status === 'ok') {
      if (withThinking) {
        setTestStatus('thinking-ok');
        setThinking(true);
        setTestInfo(`✓ Works with thinking (${data.latencyMs}ms): "${data.response}". Save the model.`);
      } else {
        setTestStatus('ok');
        setTestInfo(`Model responded (${data.latencyMs}ms): "${data.response}"`);
      }
      return;
    }
    if (data.status === 'empty_content') {
      const field = data.detectedReasoningField ?? 'reasoning';
      if (withThinking) {
        setTestStatus('failed');
        setTestInfo(`Still empty content with thinking on. Reasoning field "${field}" was present but no final answer. Try higher effort or a non-thinking sibling.`);
      } else {
        setTestStatus('needs-thinking');
        setTestInfo(`⚠ Empty content — model exposed "${field}" field (${data.latencyMs}ms). Likely a thinking model. Enable thinking and retry.`);
      }
      return;
    }
    setTestStatus('failed');
    setTestInfo(data.error || 'Unknown error');
  };

  const handleTest = async () => {
    if (!providerId || !modelId) {
      setError('Provider and Model ID are required to test.');
      return;
    }
    await runTest(thinking);
  };

  const handleRetryWithThinking = async () => {
    await runTest(true);
  };

  const handleCreate = async () => {
    if (!providerId || !modelId) {
      setError('Provider and Model ID are required.');
      return;
    }
    setError('');
    setSaving(true);
    const result = await models.create({
      providerId, modelId,
      displayName: displayName || modelId,
      tier, maxTokens,
      config: buildConfig(),
    });
    setSaving(false);
    if (!result.ok) {
      setError(result.error.message);
      return;
    }
    resetForm();
    load();
  };

  const handleUpdate = async () => {
    if (!editingId) return;
    setError('');
    setSaving(true);
    const data: Parameters<typeof models.update>[1] = {
      displayName: displayName || modelId,
      tier,
      maxTokens,
      enabled: modelEnabled,
      config: buildConfig(),
    };
    if (contextWindow !== '') data.contextWindow = contextWindow;
    const result = await models.update(editingId, data);
    setSaving(false);
    if (!result.ok) {
      setError(result.error.message);
      return;
    }
    resetForm();
    load();
  };

  const handleDelete = async (id: string) => {
    const result = await models.delete(id);
    if (!result.ok) {
      alert(result.error.message);
      return;
    }
    if (editingId === id) resetForm();
    load();
  };

  const resetTestOnChange = () => {
    if (testStatus !== 'idle') {
      setTestStatus('idle');
      setTestInfo('');
    }
  };

  const testMessageClass =
    testStatus === 'ok' || testStatus === 'thinking-ok' ? 'form-ok'
    : testStatus === 'needs-thinking' ? 'form-warn'
    : 'form-error';

  return (
    <>
      <h1>Models</h1>
      <div className="card">
        {list.length === 0 ? (
          <p className="empty">No models configured. Add a provider first, then add models.</p>
        ) : (
          <table>
            <thead><tr><th>Display Name</th><th>Model ID</th><th>Tier</th><th>Provider</th><th>Tokens</th><th>Context</th><th>Thinking</th><th>Status</th><th>Actions</th></tr></thead>
            <tbody>
              {list.map(m => (
                <tr key={m.id} className={editingId === m.id ? 'row-editing' : ''}>
                  <td><strong>{m.displayName || m.modelId}</strong></td>
                  <td style={{ fontSize: '0.8em' }}>{m.modelId}</td>
                  <td><span className={`badge ${tierBadge(m.tier)}`}>{m.tier}</span></td>
                  <td>{m.providerName}</td>
                  <td>{m.maxTokens}</td>
                  <td>{m.contextWindow ? `${Math.round(m.contextWindow / 1024)}K` : '-'}</td>
                  <td>{m.config?.thinking === true ? <span className="badge badge-warn">on ({String(m.config?.reasoning_effort ?? 'medium')})</span> : <span>-</span>}</td>
                  <td><span className={`badge ${m.enabled ? 'badge-ok' : 'badge-err'}`}>{m.enabled ? 'on' : 'off'}</span></td>
                  <td style={{ display: 'flex', gap: 4 }}>
                    <button className="sm" onClick={() => handleEdit(m)}>edit</button>
                    <button className="sm danger" onClick={() => handleDelete(m.id)}>delete</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="card">
        <h2>{editingId ? 'Edit Model' : 'Add Model'}</h2>
        {error && <p className="form-error">{error}</p>}
        <div className="form-row">
          <div><label>Provider</label>
            <select value={providerId} onChange={e => { setProviderId(e.target.value); resetTestOnChange(); }} disabled={!!editingId}>
              {providerList.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
          </div>
          <div><label>Tier</label>
            <select value={tier} onChange={e => setTier(e.target.value)}>
              <option>economy</option><option>standard</option><option>premium</option><option>enterprise</option>
            </select>
          </div>
        </div>
        <div className="form-row">
          <div><label>Model ID (sent to provider API)</label><input value={modelId} onChange={e => { setModelId(e.target.value); resetTestOnChange(); }} placeholder="eu.anthropic.claude-haiku-4-5-20251001-v1:0" disabled={!!editingId} /></div>
        </div>
        <div className="form-row">
          <div><label>Display Name</label><input value={displayName} onChange={e => setDisplayName(e.target.value)} placeholder="Claude Haiku 4.5" /></div>
          <div><label>Max Tokens</label><input type="number" value={maxTokens} onChange={e => setMaxTokens(Number(e.target.value))} min={1} /></div>
        </div>
        <div className="form-row">
          <div><label>Context Window</label><input type="number" value={contextWindow} onChange={e => setContextWindow(e.target.value ? Number(e.target.value) : '')} placeholder="131072" min={1} /></div>
          <div>
            <label>Thinking model</label>
            <select value={thinking ? 'true' : 'false'} onChange={e => { setThinking(e.target.value === 'true'); resetTestOnChange(); }}>
              <option value="false">off</option>
              <option value="true">on</option>
            </select>
          </div>
          <div>
            <label>Reasoning effort</label>
            <select value={reasoningEffort} onChange={e => { setReasoningEffort(e.target.value as EffortLevel); resetTestOnChange(); }} disabled={!thinking}>
              <option value="low">low</option>
              <option value="medium">medium</option>
              <option value="high">high</option>
            </select>
          </div>
          {editingId && (
            <div><label>Enabled</label>
              <select value={modelEnabled ? 'true' : 'false'} onChange={e => setModelEnabled(e.target.value === 'true')}>
                <option value="true">on</option>
                <option value="false">off</option>
              </select>
            </div>
          )}
        </div>
        <div className="form-row">
          <div style={{ flex: 1 }}>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              <button onClick={handleTest} disabled={testStatus === 'testing'}>
                {testStatus === 'testing' ? 'Testing...' : 'Test'}
              </button>
              {testStatus === 'needs-thinking' && (
                <button className="secondary" onClick={handleRetryWithThinking}>
                  Retry with thinking
                </button>
              )}
              {editingId ? (
                <>
                  <button onClick={handleUpdate} disabled={saving}>
                    {saving ? 'Saving...' : 'Save Changes'}
                  </button>
                  <button className="secondary" onClick={resetForm}>Cancel</button>
                </>
              ) : (
                <button onClick={handleCreate} disabled={saving}>
                  {saving ? 'Saving...' : 'Add Model'}
                </button>
              )}
            </div>
          </div>
        </div>
        {testInfo && (
          <p className={testMessageClass}>{testInfo}</p>
        )}
      </div>
    </>
  );
}

function isEffort(v: unknown): v is EffortLevel {
  return v === 'low' || v === 'medium' || v === 'high';
}

function tierBadge(tier: string) {
  return { economy: 'badge-ok', standard: 'badge-warn', premium: 'badge-warn', enterprise: 'badge-err' }[tier] || 'badge-warn';
}
