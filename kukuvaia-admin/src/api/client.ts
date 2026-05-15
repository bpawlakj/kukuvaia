const BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8080';

export interface ApiError {
  status: number;
  message: string;
}

export type ApiResult<T> = { ok: true; data: T } | { ok: false; error: ApiError };

export async function api<T>(method: string, path: string, body?: unknown): Promise<T | null> {
  const result = await apiCall<T>(method, path, body);
  return result.ok ? result.data : null;
}

export async function apiCall<T>(method: string, path: string, body?: unknown): Promise<ApiResult<T>> {
  try {
    const opts: RequestInit = {
      method,
      headers: { 'Content-Type': 'application/json' },
    };
    if (body) opts.body = JSON.stringify(body);
    const res = await fetch(`${BASE_URL}${path}`, opts);
    if (!res.ok) {
      let message = `HTTP ${res.status}`;
      try {
        const errBody = await res.json();
        if (errBody.error) message = errBody.error;
        else if (errBody.message) message = errBody.message;
      } catch { /* no JSON body */ }
      if (res.status === 401) message = 'Authentication required. Check server dev-mode or API key.';
      if (res.status === 409) message = 'Conflict — resource has active dependencies.';
      return { ok: false, error: { status: res.status, message } };
    }
    const text = await res.text();
    if (!text) return { ok: true, data: null as T };
    return { ok: true, data: JSON.parse(text) as T };
  } catch (e: unknown) {
    const message = e instanceof Error ? e.message : 'Network error';
    return { ok: false, error: { status: 0, message: `Connection failed: ${message}` } };
  }
}

// --- Types ---

export interface Provider {
  id: string;
  name: string;
  type: string;
  baseUrl: string;
  enabled: boolean;
  priority: number;
  modelCount: number;
  config: Record<string, unknown>;
  createdAt: string;
}

export interface Model {
  id: string;
  providerId: string;
  providerName: string;
  modelId: string;
  displayName: string;
  capabilities: string[];
  tier: string;
  maxTokens: number;
  contextWindow: number | null;
  enabled: boolean;
  config: Record<string, unknown>;
  discoveredAt: string | null;
  createdAt: string;
}

export interface ModelRole {
  role: string;
  modelId: string;
  modelDisplayName: string;
  modelTier: string;
  providerName: string;
  description: string | null;
}

export interface DreamReport {
  id: string;
  startedAt: string;
  completedAt: string | null;
  status: string;
  tokenCost: number;
  summary: string | null;
}

export interface DreamRecommendation {
  id: string;
  type: string;
  priority: string;
  description: string;
  suggestedAction: string;
  confidence: number;
  status: string;
}

export interface DashboardOverview {
  providerCount: number;
  modelCount: number;
  roleCount: number;
  cachedModels: number;
  cachedRoles: number;
  groupCount: number;
  ruleSetCount: number;
  pendingRecommendations: number;
  providers: { name: string; type: string; enabled: boolean; modelCount: number }[];
  roles: { role: string; model: string; tier: string; provider: string }[];
}

// --- API calls ---

export interface TestConnectionResult {
  status: string;
  latencyMs?: number;
  error?: string;
}

export interface TestModelResult {
  status: string;
  latencyMs?: number;
  response?: string;
  error?: string;
  emptyContent?: boolean;
  detectedReasoningField?: string;
  suggestedConfig?: Record<string, unknown>;
}

export const providers = {
  list: () => api<Provider[]>('GET', '/api/providers'),
  create: (data: { name: string; type: string; baseUrl: string; apiKeyRef: string; priority?: number; config?: Record<string, unknown> }) =>
    apiCall<Provider>('POST', '/api/providers', data),
  update: (id: string, data: { name?: string; type?: string; baseUrl?: string; apiKeyRef?: string; enabled?: boolean; priority?: number; config?: Record<string, unknown> }) =>
    apiCall<Provider>('PUT', `/api/providers/${id}`, data),
  delete: (id: string) => apiCall<void>('DELETE', `/api/providers/${id}`),
  syncModels: (id: string) => api<unknown>('POST', `/api/providers/${id}/sync-models`),
  testConnection: (id: string) => api<TestConnectionResult>('POST', `/api/providers/${id}/test`),
  testConnectionRaw: (data: { baseUrl: string; apiKeyRef: string; config?: Record<string, unknown> }) =>
    apiCall<TestConnectionResult>('POST', '/api/providers/test-connection', data),
};

export const models = {
  list: () => api<Model[]>('GET', '/api/models'),
  create: (data: { providerId: string; modelId: string; displayName: string; tier: string; capabilities?: string[]; maxTokens?: number; config?: Record<string, unknown> }) =>
    apiCall<Model>('POST', '/api/models', { capabilities: ['text', 'code'], maxTokens: 4096, ...data }),
  update: (id: string, data: { displayName?: string; tier?: string; capabilities?: string[]; maxTokens?: number; contextWindow?: number; enabled?: boolean; config?: Record<string, unknown> }) =>
    apiCall<Model>('PUT', `/api/models/${id}`, data),
  delete: (id: string) => apiCall<void>('DELETE', `/api/models/${id}`),
  test: (data: { providerId: string; modelId: string; config?: Record<string, unknown> }) =>
    apiCall<TestModelResult>('POST', '/api/models/test', data),
};

export const roles = {
  list: () => api<ModelRole[]>('GET', '/api/models/roles'),
  assign: (role: string, modelId: string, description?: string) =>
    api<ModelRole>('PUT', `/api/models/roles/${role}`, { modelId, description }),
  delete: (role: string) => api<void>('DELETE', `/api/models/roles/${role}`),
};

export const dashboard = {
  overview: () => api<DashboardOverview>('GET', '/api/dashboard/overview'),
  health: () => api<Record<string, unknown>>('GET', '/api/dashboard/health'),
};

export const dream = {
  trigger: () => api<{ reportId: string }>('POST', '/api/dream/trigger'),
  reports: () => api<DreamReport[]>('GET', '/api/dream/reports?limit=10'),
  latest: () => api<DreamReport>('GET', '/api/dream/reports/latest'),
  pending: () => api<DreamRecommendation[]>('GET', '/api/dream/recommendations/pending'),
  accept: (id: string) => api<void>('POST', `/api/dream/recommendations/${id}/accept`),
  reject: (id: string, reason: string) => api<void>('POST', `/api/dream/recommendations/${id}/reject`, { reason }),
};
