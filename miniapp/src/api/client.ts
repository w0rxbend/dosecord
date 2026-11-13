import { OFF } from './types';
import type {
  AccountSettings,
  AccountSettingsInput,
  BlobAsset,
  Consents,
  Domain,
  InsightCalendar,
  InsightRange,
  MoodCheckin,
  MoodCheckinInput,
  MoodDay,
  MoodRecorded,
  MoodScale,
  MoodSummary,
  NotificationSettings,
  NotificationSettingsInput,
  Off,
  SleepCheckinKind,
  SleepEpisode,
  SleepEpisodeInput,
  SleepSettings,
  SleepStatus,
  SleepSummary,
  TimeZoneChoice,
  TimeZonePreview,
  WaterDay,
  WaterEntry,
  WaterLogged,
  WaterSettings,
  WaterSettingsInput,
  WaterSummary,
  InsightsOverview,
  LocalDate,
  Medication,
  MedicationDraft,
  MedicationOptions,
  MedicationSummary,
  OccurrenceAction,
  OccurrenceActionResult,
  Schedule,
  ScheduleDraft,
  ScheduleOptions,
  SchedulePreview,
  TodayView,
} from './types';

export interface FieldError {
  readonly field: string;
  readonly code: string;
}

export type ApiErrorCode =
  | 'network'
  | 'timeout'
  | 'unauthorised'
  | 'account_required'
  | 'consent_required'
  | 'client_too_old'
  | 'unavailable'
  | 'not_found'
  | 'validation'
  | 'version_conflict'
  | 'quota_exceeded'
  | 'stepup_required'
  | 'server';

/** A problem document mapped to a typed error; screens map codes, never message texts (8.3). */
export class ApiError extends Error {
  constructor(
    readonly code: ApiErrorCode,
    readonly reference?: string,
    readonly fields: readonly FieldError[] = [],
  ) {
    super(code);
    this.name = 'ApiError';
  }
}

export function toApiError(cause: unknown): ApiError {
  return cause instanceof ApiError ? cause : new ApiError('server');
}

/**
 * Everything the screens ask of the backend, named after the resources of MINI_APP 15.2.
 * The mock backend and the HTTP client both implement it.
 */
export interface ApiClient {
  today(date?: LocalDate): Promise<TodayView>;
  /** Command on occurrences/{id}/actions; the key is generated when the user acts (MA-CONC-1). */
  actOnOccurrence(id: string, action: OccurrenceAction, idempotencyKey: string): Promise<OccurrenceActionResult>;

  // hydration (MINI_APP 7.7): a write carries the key made when the person tapped (MA-HYD-1).
  waterSummary(): Promise<WaterSummary>;
  waterEntries(from: LocalDate, to: LocalDate): Promise<readonly WaterEntry[]>;
  addWater(amountMl: number, idempotencyKey: string): Promise<WaterLogged>;
  removeWater(id: string): Promise<WaterDay>;
  waterSettings(): Promise<WaterSettings>;
  saveWaterSettings(input: WaterSettingsInput, version: number): Promise<WaterSettings>;

  // mood (MINI_APP 7.9)
  moodScale(): Promise<MoodScale>;
  blobStates(revision: string): Promise<BlobAsset>;
  moodSummary(): Promise<MoodSummary>;
  moodCheckins(from: LocalDate, to: LocalDate): Promise<readonly MoodCheckin[]>;
  checkIn(input: MoodCheckinInput, idempotencyKey: string): Promise<MoodRecorded>;
  removeCheckin(id: string): Promise<MoodDay>;

  // sleep (MINI_APP 7.10): a write carries the key made when the person acted.
  sleepSummary(): Promise<SleepSummary>;
  sleepEpisodes(from: LocalDate, to: LocalDate): Promise<readonly SleepEpisode[]>;
  addSleep(input: SleepEpisodeInput, idempotencyKey: string): Promise<SleepEpisode>;
  removeSleep(id: string): Promise<SleepStatus>;
  sleepCheckIn(kind: SleepCheckinKind, idempotencyKey: string): Promise<SleepStatus>;
  sleepSettings(): Promise<SleepSettings>;
  saveSleepSettings(goalMinutes: number | undefined, version: number): Promise<SleepSettings>;

  // history calendar (MINI_APP 7.12): one month of one domain; `month` is YYYY-MM.
  calendar(domain: Domain, month?: string): Promise<InsightCalendar>;

  // settings and consent (MINI_APP 7.15)
  settings(): Promise<AccountSettings>;
  saveSettings(input: AccountSettingsInput, version: number): Promise<AccountSettings>;
  notificationSettings(): Promise<NotificationSettings>;
  saveNotificationSettings(input: NotificationSettingsInput, version: number): Promise<NotificationSettings>;
  timeZones(): Promise<readonly TimeZoneChoice[]>;
  previewTimeZone(timeZone: string): Promise<TimeZonePreview>;
  consents(): Promise<Consents>;

  medications(): Promise<readonly MedicationSummary[]>;
  medication(id: string): Promise<Medication>;
  medicationOptions(): Promise<MedicationOptions>;
  createMedication(draft: MedicationDraft): Promise<Medication>;
  updateMedication(id: string, draft: MedicationDraft, version: number): Promise<Medication>;

  scheduleOptions(): Promise<ScheduleOptions>;
  previewSchedule(medicationId: string, draft: ScheduleDraft): Promise<SchedulePreview>;
  createSchedule(medicationId: string, draft: ScheduleDraft): Promise<Schedule>;

  insightsOverview(rangeDays: InsightRange): Promise<InsightsOverview>;
}

/**
 * A read of one domain: its answer, or OFF when the domain's consent is missing. The server is
 * the authority on consent (ARCHITECTURE API-19); the screen shows "turned off", not an error.
 */
export async function unlessOff<T>(read: Promise<T>): Promise<T | Off> {
  try {
    return await read;
  } catch (cause) {
    if (cause instanceof ApiError && cause.code === 'consent_required') return OFF;
    throw cause;
  }
}

export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
