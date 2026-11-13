// The only module that calls fetch (MA-STACK-3). Not yet exercised against the real backend:
// paths follow MINI_APP 15.2, and response bodies are assumed to match ./types until the
// generated OpenAPI types replace them.
import { ApiError, type ApiClient, type ApiErrorCode, type FieldError } from './client';

const TIMEOUT_MS = 10_000;

interface Problem {
  code?: string;
  reference?: string;
  fields?: FieldError[];
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH';
  body?: unknown;
  headers?: Record<string, string>;
}

function codeFor(status: number): ApiErrorCode {
  if (status === 401) return 'unauthorised';
  if (status === 404) return 'not_found';
  if (status === 409 || status === 412) return 'version_conflict';
  if (status === 422) return 'validation';
  return 'server';
}

async function problemOf(response: Response): Promise<ApiError> {
  const problem = (await response.json().catch(() => ({}))) as Problem;
  return new ApiError(codeFor(response.status), problem.reference, problem.fields ?? []);
}

async function send(url: string, token: string | null, options: RequestOptions): Promise<Response> {
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), TIMEOUT_MS);
  try {
    return await fetch(url, {
      method: options.method ?? 'GET',
      cache: 'no-store',
      signal: abort.signal,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      headers: {
        ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(token === null ? {} : { Authorization: `Bearer ${token}` }),
        ...options.headers,
      },
    });
  } catch {
    throw new ApiError(abort.signal.aborted ? 'timeout' : 'network');
  } finally {
    clearTimeout(timer);
  }
}

export interface HttpApiConfig {
  readonly baseUrl: string;
  readonly initData: string;
  readonly colorScheme: 'light' | 'dark';
}

export function createHttpApi(config: HttpApiConfig): ApiClient {
  // The session token lives in memory only (MINI_APP 6.6).
  let session: Promise<string> | null = null;

  async function openSession(): Promise<string> {
    const response = await send(`${config.baseUrl}/session`, null, {
      method: 'POST',
      body: { initData: config.initData, colorScheme: config.colorScheme },
    });
    if (!response.ok) throw await problemOf(response);
    return ((await response.json()) as { token: string }).token;
  }

  async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    session ??= openSession().catch((cause: unknown) => {
      session = null;
      throw cause;
    });
    const response = await send(`${config.baseUrl}/${path}`, await session, options);
    if (!response.ok) throw await problemOf(response);
    return (await response.json()) as T;
  }

  const command = (key: string) => ({ 'Idempotency-Key': key });
  const id = encodeURIComponent;

  return {
    today: (date) => request(date ? `today?date=${id(date)}` : 'today'),
    actOnOccurrence: (occurrenceId, action, key) =>
      request(`occurrences/${id(occurrenceId)}/actions`, { method: 'POST', body: action, headers: command(key) }),
    addHydration: (amountMl, key) =>
      request('hydration/entries', { method: 'POST', body: { amountMl }, headers: command(key) }),

    medications: () => request('medications'),
    medication: (medicationId) => request(`medications/${id(medicationId)}`),
    medicationOptions: () => request('medication-options'),
    createMedication: (draft) => request('medications', { method: 'POST', body: draft }),
    updateMedication: (medicationId, draft, version) =>
      request(`medications/${id(medicationId)}`, {
        method: 'PATCH',
        body: draft,
        headers: { 'If-Match': `"${version}"` },
      }),

    scheduleOptions: () => request('schedule-options'),
    previewSchedule: (medicationId, draft) =>
      request('schedule-previews', { method: 'POST', body: { medicationId, draft } }),
    createSchedule: (medicationId, draft) =>
      request(`medications/${id(medicationId)}/schedules`, { method: 'POST', body: draft }),

    insightsOverview: (rangeDays) => request(`insights/overview?range=${rangeDays}`),
  };
}
