// The only module that calls fetch (MA-STACK-3). Wire types are generated from the backend's
// OpenAPI document (./schema.d.ts); ./wire.ts maps them to the types the screens use.
import { ApiError, newIdempotencyKey, type ApiClient, type ApiErrorCode } from './client';
import {
  toAccountSettings,
  toBlobAsset,
  toCalendar,
  toConsents,
  toFieldError,
  toInsightList,
  toInsightMonth,
  toInsightsOverview,
  toMeal,
  toMealRecorded,
  toMoodScale,
  toMoodSummary,
  toNotificationSettings,
  toNutritionDay,
  toNutritionSettings,
  toNutritionSummary,
  toWaterSettings,
  toSleepStatus,
  toDayProfile,
  toExportItem,
  toReminderPresets,
  toReminders,
  toReminderTimeline,
  toSleepSummary,
  toWaterSummary,
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
  method?: 'GET' | 'POST' | 'PATCH' | 'DELETE';
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
  quota_exceeded: 'quota_exceeded',
  stepup_required: 'stepup_required',
  stepup_pending: 'stepup_pending',
  rate_limited: 'rate_limited',
};

/** A problem document becomes a typed error; its code is mapped, its title is never shown (8.3). */
async function problemOf(response: Response): Promise<ApiError> {
  const problem = (await response.json().catch(() => ({}))) as Partial<Wire['Problem']>;
  const code = (problem.code && CODES[problem.code]) || 'server';
  const retryAfter = Number(response.headers.get('Retry-After') ?? '');
  return new ApiError(
    code,
    problem.requestId,
    (problem.errors ?? []).map(toFieldError),
    Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter : undefined,
  );
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
  const range = (from: string, to: string) => `from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`;
  const versionTag = (version: number) => ({ 'If-Match': `"v${version}"` });
  const id = encodeURIComponent;
  /** A resource of MINI_APP 15.2 that the server does not have yet. */
  const unavailable = () => Promise.reject(new ApiError('unavailable'));

  const client: ApiClient = {
    today: async (date) => toTodayView(await request<Wire['DayView']>(date ? `days/${id(date)}` : 'today')),
    actOnOccurrence: unavailable,

    waterSummary: async () => toWaterSummary(await request<Wire['HydrationSummary']>('hydration/summary')),
    waterEntries: async (from, to) =>
      (await request<Wire['HydrationEntries']>(`hydration/entries?${range(from, to)}`)).items ?? [],
    addWater: async (amountMl, key) => {
      const body: Wire['HydrationEntryInput'] = { amountMl };
      return request<Wire['HydrationLogged']>('hydration/entries', { method: 'POST', body, headers: command(key) });
    },
    removeWater: (entryId) => request<Wire['HydrationDayView']>(`hydration/entries/${id(entryId)}`, { method: 'DELETE' }),
    waterSettings: async () => toWaterSettings(await request<Wire['HydrationSettings']>('hydration/settings')),
    saveWaterSettings: async (input, version) => {
      const body: Wire['HydrationSettingsInput'] = { presetsMl: [...input.presetsMl], goalMl: input.goalMl };
      const saved = await request<Wire['HydrationSettings']>('hydration/settings', {
        method: 'PATCH',
        body,
        headers: versionTag(version),
      });
      return toWaterSettings(saved);
    },

    moodScale: async () => toMoodScale(await request<Wire['MoodScale']>('mood/scale')),
    blobStates: async (revision) =>
      toBlobAsset(await request<Wire['MoodBlobStates']>(`mood/blob-states?revision=${id(revision)}`)),
    moodSummary: async () => toMoodSummary(await request<Wire['MoodSummary']>('mood/summary')),
    moodCheckins: async (from, to) =>
      (await request<Wire['MoodCheckins']>(`mood/checkins?${range(from, to)}`)).items ?? [],
    checkIn: (input, key) => {
      const body: Wire['MoodCheckinInput'] = { valence: input.valence, energy: input.energy };
      return request<Wire['MoodRecordedView']>('mood/checkins', { method: 'POST', body, headers: command(key) });
    },
    removeCheckin: (checkinId) => request<Wire['MoodDayView']>(`mood/checkins/${id(checkinId)}`, { method: 'DELETE' }),

    sleepSummary: async () => toSleepSummary(await request<Wire['SleepSummary']>('sleep/summary')),
    sleepEpisodes: async (from, to) =>
      (await request<Wire['SleepEpisodes']>(`sleep/episodes?${range(from, to)}`)).items ?? [],
    addSleep: (input, key) => {
      const body: Wire['SleepEpisodeInput'] = { ...input };
      return request<Wire['SleepEpisodeView']>('sleep/episodes', { method: 'POST', body, headers: command(key) });
    },
    removeSleep: async (episodeId) =>
      toSleepStatus(await request<Wire['SleepStatusView']>(`sleep/episodes/${id(episodeId)}`, { method: 'DELETE' })),
    sleepCheckIn: async (kind, key) => {
      const body: Wire['SleepCheckinInput'] = { kind };
      return toSleepStatus(
        await request<Wire['SleepStatusView']>('sleep/checkins', { method: 'POST', body, headers: command(key) }),
      );
    },
    sleepSettings: () => request<Wire['SleepSettings']>('sleep/settings'),
    saveSleepSettings: (goalMinutes, version) => {
      const body: Wire['SleepSettingsInput'] = { goalMinutes };
      return request<Wire['SleepSettings']>('sleep/settings', { method: 'PATCH', body, headers: versionTag(version) });
    },

    nutritionSummary: async (from, to) => {
      const query = from !== undefined && to !== undefined ? `?${range(from, to)}` : '';
      return toNutritionSummary(await request<Wire['NutritionSummary']>(`nutrition/summary${query}`));
    },
    meals: async (from, to) => ((await request<Wire['Meals']>(`meals?${range(from, to)}`)).items ?? []).map(toMeal),
    addMeal: async (input, key) => {
      const body: Wire['MealEntryInput'] = { ...input };
      return toMealRecorded(
        await request<Wire['MealRecordedView']>('meals', { method: 'POST', body, headers: command(key) }),
      );
    },
    removeMeal: async (mealId) =>
      toNutritionDay(await request<Wire['NutritionDay']>(`meals/${id(mealId)}`, { method: 'DELETE' })),
    recentMeals: async (mealType) =>
      (await request<Wire['MealRecent']>(`meals/recent?mealType=${id(mealType)}`)).descriptions ?? [],
    nutritionSettings: async () => toNutritionSettings(await request<Wire['NutritionSettings']>('nutrition/settings')),
    saveNutritionSettings: async (planned, version) => {
      const body: Wire['NutritionSettingsInput'] = { planned: [...planned] };
      return toNutritionSettings(
        await request<Wire['NutritionSettings']>('nutrition/settings', {
          method: 'PATCH',
          body,
          headers: versionTag(version),
        }),
      );
    },

    reminders: async () => toReminders(await request<Wire['RemindersView']>('reminders')),
    reminderPresets: async () => toReminderPresets(await request<Wire['ReminderPresets']>('reminders/presets')),
    turnOnReminder: async (presetId, key) => {
      const body: Wire['ReminderPlanInput'] = { presetId };
      return toReminders(
        await request<Wire['RemindersView']>('reminders/plans', { method: 'POST', body, headers: command(key) }),
      );
    },
    turnOffReminder: async (kind) =>
      toReminders(await request<Wire['RemindersView']>(`reminders/plans/${id(kind)}`, { method: 'DELETE' })),
    pauseReminders: async (hours, key) => {
      const body: Wire['ReminderPauseInput'] = { hours };
      return toReminders(
        await request<Wire['RemindersView']>('reminders/pause', { method: 'POST', body, headers: command(key) }),
      );
    },
    saveDayProfile: async (input, version) => {
      // Named field by field: a form's copy of the day also holds its version and saved flag (ADR-023).
      const body: Wire['DayProfileInput'] = {
        wake: input.wake,
        breakfast: input.breakfast,
        lunch: input.lunch,
        dinner: input.dinner,
        bedtime: input.bedtime,
        workDays: [...input.workDays],
        workStart: input.workStart,
        workEnd: input.workEnd,
        quietStart: input.quietStart,
        quietEnd: input.quietEnd,
      };
      return toDayProfile(
        await request<Wire['DayProfileView']>('reminders/day', { method: 'PATCH', body, headers: versionTag(version) }),
      );
    },
    reminderTimeline: async () => toReminderTimeline(await request<Wire['ReminderTimeline']>('reminders/timeline')),

    calendar: async (domain, month) => {
      const query = month === undefined ? '' : `&month=${id(month)}`;
      return toCalendar(await request<Wire['InsightCalendar']>(`insights/calendar?domain=${domain}${query}`));
    },

    settings: async () => toAccountSettings(await request<Wire['SettingsView']>('settings')),
    saveSettings: async (input, version) => {
      const body: Wire['SettingsInput'] = { ...input };
      return toAccountSettings(
        await request<Wire['SettingsView']>('settings', { method: 'PATCH', body, headers: versionTag(version) }),
      );
    },
    notificationSettings: async () =>
      toNotificationSettings(await request<Wire['NotificationSettingsView']>('notification-settings')),
    saveNotificationSettings: async (input, version) => {
      const body: Wire['NotificationSettingsInput'] = { ...input };
      const saved = await request<Wire['NotificationSettingsView']>('notification-settings', {
        method: 'PATCH',
        body,
        headers: versionTag(version),
      });
      return toNotificationSettings(saved);
    },
    timeZones: async () => (await request<Wire['TimeZoneList']>('time-zones')).items ?? [],
    previewTimeZone: async (timeZone) => {
      const body: Wire['TimeZonePreviewRequest'] = { timeZone };
      const preview = await request<Wire['TimeZonePreview']>('time-zone-previews', { method: 'POST', body });
      return { ...preview, lines: preview.lines ?? [] };
    },
    consents: async () => toConsents(await request<Wire['ConsentList']>('consents')),

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
    schedules: async (medicationId) =>
      ((await request<Wire['ScheduleList']>(`medications/${id(medicationId)}/schedules`)).items ?? []).map(toSchedule),
    schedule: async (scheduleId) => toSchedule(await request<Wire['Schedule']>(`schedules/${id(scheduleId)}`)),
    reviseSchedule: async (scheduleId, draft, revision) =>
      toSchedule(
        await request<Wire['Schedule']>(`schedules/${id(scheduleId)}/revisions`, {
          method: 'POST',
          body: toScheduleDraft(draft),
          headers: versionTag(revision),
        }),
      ),

    insightsOverview: async (rangeDays) =>
      toInsightsOverview(await request<Wire['InsightsOverview']>(`insights/overview?range=${rangeDays}`), rangeDays),
    insightMonth: async (month) => {
      const query = month === undefined ? '' : `?month=${id(month)}`;
      return toInsightMonth(await request<Wire['InsightMonth']>(`insights/month${query}`));
    },
    insightList: async () => toInsightList(await request<Wire['InsightList']>('insights/correlations')),

    exports: async () => ((await request<Wire['ExportList']>('exports')).items ?? []).map(toExportItem),
    exportItem: async (exportId) => toExportItem(await request<Wire['ExportView']>(`exports/${id(exportId)}`)),
    requestExport: async (input, key) => {
      const body: Wire['ExportInput'] = { ...input };
      return toExportItem(await request<Wire['ExportView']>('exports', { method: 'POST', body, headers: command(key) }));
    },
    downloadTicket: async (exportId) => {
      const ticket = await request<Wire['DownloadTicket']>(`exports/${id(exportId)}/download-tickets`, {
        method: 'POST',
      });
      // The ticket path is on this origin; downloadFile needs it absolute.
      return { ...ticket, url: new URL(ticket.url, globalThis.location?.href ?? 'http://localhost/').toString() };
    },
  };

  return { open, client };
}
