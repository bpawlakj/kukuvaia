import { useEffect, useState } from 'react';
import { dream, type DreamReport, type DreamRecommendation } from '../api/client';

export function DreamPage() {
  const [reports, setReports] = useState<DreamReport[]>([]);
  const [pending, setPending] = useState<DreamRecommendation[]>([]);
  const [triggering, setTriggering] = useState(false);

  const load = () => {
    dream.reports().then(d => setReports(d || []));
    dream.pending().then(d => setPending(d || []));
  };
  useEffect(load, []);

  const handleTrigger = async () => {
    setTriggering(true);
    await dream.trigger();
    setTimeout(() => { load(); setTriggering(false); }, 1000);
  };

  const handleAccept = async (id: string) => {
    await dream.accept(id);
    load();
  };

  const handleReject = async (id: string) => {
    await dream.reject(id, 'dismissed from admin panel');
    load();
  };

  return (
    <>
      <h1>Dreaming</h1>

      <div className="card">
        <button onClick={handleTrigger} disabled={triggering}>
          {triggering ? 'Running...' : 'Trigger Dream Run'}
        </button>
      </div>

      {pending.length > 0 && (
        <div className="card">
          <h2>Pending Recommendations</h2>
          <table>
            <thead><tr><th>Type</th><th>Priority</th><th>Description</th><th>Confidence</th><th>Actions</th></tr></thead>
            <tbody>
              {pending.map(r => (
                <tr key={r.id}>
                  <td>{r.type}</td>
                  <td><span className={`badge ${priorityBadge(r.priority)}`}>{r.priority}</span></td>
                  <td style={{ fontSize: '0.85em' }}>{r.description}</td>
                  <td>{Math.round(r.confidence * 100)}%</td>
                  <td style={{ display: 'flex', gap: 4 }}>
                    <button className="sm" onClick={() => handleAccept(r.id)}>accept</button>
                    <button className="sm danger" onClick={() => handleReject(r.id)}>reject</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="card">
        <h2>Recent Reports</h2>
        {reports.length === 0 ? (
          <p className="empty">No dream reports yet. Trigger a run above.</p>
        ) : (
          <table>
            <thead><tr><th>Started</th><th>Status</th><th>Tokens</th><th>Summary</th></tr></thead>
            <tbody>
              {reports.map(r => (
                <tr key={r.id}>
                  <td style={{ fontSize: '0.8em' }}>{new Date(r.startedAt).toLocaleString()}</td>
                  <td><span className={`badge ${r.status === 'completed' ? 'badge-ok' : r.status === 'failed' ? 'badge-err' : 'badge-warn'}`}>{r.status}</span></td>
                  <td>{r.tokenCost}</td>
                  <td style={{ fontSize: '0.85em' }}>{r.summary || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}

function priorityBadge(p: string) {
  return { critical: 'badge-err', high: 'badge-err', medium: 'badge-warn', low: 'badge-ok', informational: 'badge-ok' }[p] || 'badge-warn';
}
