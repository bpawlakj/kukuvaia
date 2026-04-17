import { useEffect, useState } from 'react';
import { dashboard, type DashboardOverview } from '../api/client';

export function DashboardPage() {
  const [data, setData] = useState<DashboardOverview | null>(null);

  useEffect(() => {
    dashboard.overview().then(setData);
  }, []);

  if (!data) return <p className="empty">Connecting to kukuvaia-engine...</p>;

  return (
    <>
      <h1>Dashboard</h1>
      <div className="card">
        <table>
          <thead>
            <tr>
              <th>Providers</th><th>Models</th><th>Roles</th><th>Cached</th>
              <th>Groups</th><th>Rule Sets</th><th>Pending Recs</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>{data.providerCount}</td>
              <td>{data.modelCount}</td>
              <td>{data.roleCount}</td>
              <td>{data.cachedModels}</td>
              <td>{data.groupCount}</td>
              <td>{data.ruleSetCount}</td>
              <td>{data.pendingRecommendations}</td>
            </tr>
          </tbody>
        </table>
      </div>

      {data.roles.length > 0 && (
        <div className="card">
          <h2>Active Roles</h2>
          <table>
            <thead><tr><th>Role</th><th>Model</th><th>Tier</th><th>Provider</th></tr></thead>
            <tbody>
              {data.roles.map(r => (
                <tr key={r.role}>
                  <td><strong>{r.role}</strong></td>
                  <td>{r.model}</td>
                  <td><span className={`badge ${tierBadge(r.tier)}`}>{r.tier}</span></td>
                  <td>{r.provider}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {data.providers.length > 0 && (
        <div className="card">
          <h2>Providers</h2>
          <table>
            <thead><tr><th>Name</th><th>Type</th><th>Models</th><th>Status</th></tr></thead>
            <tbody>
              {data.providers.map(p => (
                <tr key={p.name}>
                  <td>{p.name}</td>
                  <td>{p.type}</td>
                  <td>{p.modelCount}</td>
                  <td><span className={`badge ${p.enabled ? 'badge-ok' : 'badge-err'}`}>{p.enabled ? 'active' : 'disabled'}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}

function tierBadge(tier: string) {
  return { economy: 'badge-ok', standard: 'badge-warn', premium: 'badge-warn', enterprise: 'badge-err' }[tier] || 'badge-warn';
}
