import { useEffect, useState } from 'react';
import { roles, models, type ModelRole, type Model } from '../api/client';

export function RolesPage() {
  const [list, setList] = useState<ModelRole[]>([]);
  const [modelList, setModelList] = useState<Model[]>([]);
  const [role, setRole] = useState('supervisor');
  const [modelId, setModelId] = useState('');
  const [description, setDescription] = useState('');
  const [editingRole, setEditingRole] = useState<string | null>(null);

  const load = () => {
    roles.list().then(d => setList(d || []));
    models.list().then(d => { setModelList(d || []); if (d?.length && !modelId) setModelId(d[0].id); });
  };
  useEffect(load, []);

  const resetForm = () => {
    setRole('supervisor'); setDescription('');
    setEditingRole(null);
    if (modelList.length) setModelId(modelList[0].id);
  };

  const handleEdit = (r: ModelRole) => {
    setEditingRole(r.role);
    setRole(r.role);
    setModelId(r.modelId);
    setDescription(r.description || '');
  };

  const handleAssign = async () => {
    if (!role || !modelId) return;
    await roles.assign(role, modelId, description || undefined);
    resetForm();
    load();
  };

  const handleDelete = async (r: string) => {
    await roles.delete(r);
    if (editingRole === r) resetForm();
    load();
  };

  return (
    <>
      <h1>Role Assignments</h1>
      <div className="card">
        {list.length === 0 ? (
          <p className="empty">No roles assigned. Add models first, then assign roles.</p>
        ) : (
          <table>
            <thead><tr><th>Role</th><th>Model</th><th>Tier</th><th>Provider</th><th>Description</th><th>Actions</th></tr></thead>
            <tbody>
              {list.map(r => (
                <tr key={r.role} className={editingRole === r.role ? 'row-editing' : ''}>
                  <td><strong>{r.role}</strong></td>
                  <td>{r.modelDisplayName}</td>
                  <td><span className={`badge ${tierBadge(r.modelTier)}`}>{r.modelTier}</span></td>
                  <td>{r.providerName}</td>
                  <td style={{ fontSize: '0.85em' }}>{r.description || '-'}</td>
                  <td style={{ display: 'flex', gap: 4 }}>
                    <button className="sm" onClick={() => handleEdit(r)}>edit</button>
                    <button className="sm danger" onClick={() => handleDelete(r.role)}>remove</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="card">
        <h2>{editingRole ? `Edit Role: ${editingRole}` : 'Assign Role'}</h2>
        <div className="form-row">
          <div><label>Role</label>
            {editingRole ? (
              <input value={role} disabled />
            ) : (
              <select value={role} onChange={e => setRole(e.target.value)}>
                <option>worker</option><option>worker-2</option><option>worker-3</option>
                <option>supervisor</option><option>advisor</option>
              </select>
            )}
          </div>
          <div><label>Model</label>
            <select value={modelId} onChange={e => setModelId(e.target.value)}>
              {modelList.map(m => <option key={m.id} value={m.id}>{m.displayName || m.modelId} ({m.tier})</option>)}
            </select>
          </div>
        </div>
        <div className="form-row">
          <div><label>Description</label><input value={description} onChange={e => setDescription(e.target.value)} placeholder="Strategic advisor for architecture decisions" /></div>
          <div><label>&nbsp;</label>
            <div style={{ display: 'flex', gap: 6 }}>
              <button onClick={handleAssign}>{editingRole ? 'Save Changes' : 'Assign Role'}</button>
              {editingRole && <button className="secondary" onClick={resetForm}>Cancel</button>}
            </div>
          </div>
        </div>
      </div>
    </>
  );
}

function tierBadge(tier: string) {
  return { economy: 'badge-ok', standard: 'badge-warn', premium: 'badge-warn', enterprise: 'badge-err' }[tier] || 'badge-warn';
}
