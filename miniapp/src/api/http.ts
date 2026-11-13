// The only module that calls fetch (MA-STACK-3). Wire types are generated from the backend's
// OpenAPI document (./schema.d.ts); ./wire.ts maps them to the types the screens use.
import { ApiError, newIdempotencyKey, type ApiClient, type ApiErrorCode } from './client';
import {
  toFieldError,
  toInsightsOverview,
  toMedication,
  toMedicationInput,
  toMedicationOptions,
  toMedicationSummary,
  toSchedule,
  toScheduleDraft,
  toScheduleOptions,
  toSchedulePreview,
  toTodayView,
  type Wire,
} from './wire';

const TIMEOUT_MS = 10_000;
/** The server accepts launch data for 300 seconds after auth_date (ARCHITECTURE API-06). */
const FRESHNESS_MS = 300_000;

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH';
  body?: unknown;
  headers?: Record<string, string>;
}

const CODES: Readonly<Record<string, ApiErrorCode>> = {
  unauthenticated: 'unauthorised',
  session_expired: 'unauthorised',
  client_too_old: 'client_too_old',
  account_required: 'account_required',
  consent_required: 'consent_required',
  not_found: 'not_found',
  version_conflict: 'version_conflict',
  precondition_required: 'version_conflict',
  validation_failed: 'validation',
  idempotency_key_reuse: 'validation',
};

/** A problem document becomes a typed error; its code is mapped, its title is never shown (8.3). */
async function problemOf(response: Response): Promise<ApiError> {
  const problem = (await response.json().catch(() => ({}))) as Partial<Wire['Problem']>;
  const code = (problem.code && CODES[problem.code]) || 'server';
  return new ApiError(code, problem.requestId, (problem.errors ?? []).map(toFieldError));
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

/** auth_date of the launch data in milliseconds, read locally to know the freshness window. */
function authDateOf(initData: string): number {
  return Number(new URLSearchParams(initData).get('auth_date') ?? '0') * 1000;
}

export interface HttpApiConfig {
  readonly baseUrl: string;
  /** Sent to POST session and nowhere else (MA-LAUNCH-4). */
  readonly initData: string;
  readonly colorScheme: 'light' | 'dark';
  readonly platform?: string;
  /** Called once when the session is over and cannot be renewed (gate G-4). */
  readonly onSessionEnded?: () => void;
  readonly now?: () => number;
}

export interface HttpApi {
  /** The session exchange (MINI_APP 3.2 step 6). Rejects with the code the gate screens map. */
  open(): Promise<Wire['Bootstrap']>;
  readonly client: ApiClient;
}

export function createHttpApi(config: HttpApiConfig): HttpApi {
  const now = config.now ?? Date.now;
  // The session token lives in this closure only (MA-LAUNCH-5).
  let token: string | null = null;

  async function open(): Promise<Wire['Bootstrap']> {
    const body: Wire['SessionRequest'] = {
      initData: config.initData,
      deviceTimeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      platform: config.platform,
      colorScheme: config.colorScheme,
    };
    const response = await send(`${config.baseUrl}/session`, null, { method: 'POST', body });
    if (!response.ok) throw await problemOf(response);
    const created = (await response.json()) as Wire['SessionCreated'];
    token = created.token;
    return created.bootstrap;
  }

  /** Inside the freshness window a 401 is answered by one silent exchange (MA-GATE-2). */
  async function renew(): Promise<boolean> {
    if (now() - authDateOf(config.initData) >= FRESHNESS_MS) return false;
    try {
      await open();
      return true;
    } catch {
      return false;
    }
  }

  async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    let response = await send(`${config.baseUrl}/${path}`, token, options);
    if (response.status === 401 && (await renew())) {
      response = await send(`${config.baseUrl}/${path}`, token, options);
    }
    if (response.status === 401) {
      token = null;
      config.onSessionEnded?.();
    }
    if (!response.ok) throw await problemOf(response);
    return (await response.json()) as T;
  }

  const command = (key: string) => ({ 'Idempotency-Key': key });
  const versionTag = (version: number) => ({ 'If-Match': `"v${version}"` });
  const id = encodeURIComponent;
  /** A resource of MINI_APP 15.2 that the server does not have yet. */
  const unavailable = () => Promise.reject(new ApiError('unavailable'));

  const client: ApiClient = {
    today: async (date) => toTodayView(await request<Wire['DayView']>(date ? `days/${id(date)}` : 'today')),
    actOnOccurrence: unavailable,
    addHydration: unavailable,

    medications: async () => ((await request<Wire['MedicationList']>('medications')).items ?? []).map(toMedicationSummary),
    medication: async (medicationId) => toMedication(await request<Wire['Medication']>(`medications/${id(medicationId)}`)),
    medicationOptions: async () => toMedicationOptions(await request<Wire['MedicationOptions']>('medication-options')),
    createMedication: async (draft) =>
      toMedication(
        await request<Wire['Medication']>('medications', {
          method: 'POST',
          body: toMedicationInput(draft),
          headers: command(newIdempotencyKey()),
        }),
      ),
    updateMedication: async (medicationId, draft, version) =>
      toMedication(
        await request<Wire['Medication']>(`medications/${id(medicationId)}`, {
          method: 'PATCH',
          body: toMedicationInput(draft),
          headers: versionTag(version),
        }),
      ),

    scheduleOptions: async () => toScheduleOptions(await request<Wire['ScheduleOptions']>('schedule-options')),
    previewSchedule: async (_medicationId, draft) => {
      const body: Wire['SchedulePreviewRequest'] = { draft: toScheduleDraft(draft) };
      return toSchedulePreview(await request<Wire['SchedulePreview']>('schedule-previews', { method: 'POST', body }));
    },
    createSchedule: async (medicationId, draft) =>
      toSchedule(
        await request<Wire['Schedule']>(`medications/${id(medicationId)}/schedules`, {
          method: 'POST',
          body: toScheduleDraft(draft),
          headers: command(newIdempotencyKey()),
        }),
      ),

    insightsOverview: async (rangeDays) =>
      toInsightsOverview(await request<Wire['InsightsOverview']>(`insights/overview?range=${rangeDays}`), rangeDays),
  };

  return { open, client };
}
