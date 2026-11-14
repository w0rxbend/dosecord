import { OFF } from './types';
import type {
  AccountSettings,
  AccountSettingsInput,
  BlobAsset,
  Consents,
  Domain,
  InsightCalendar,
  InsightList,
  InsightMonth,
  InsightRange,
  MoodCheckin,
  MoodCheckinInput,
  MoodDay,
  MoodRecorded,
  MoodScale,
  MoodSummary,
  NotificationSettings,
  NotificationSettingsInput,
  NutritionDay,
  NutritionSettings,
  NutritionSummary,
  Off,
  SleepCheckinKind,
  DayProfile,
  DayProfileInput,
  ExportItem,
  ExportRequest,
  ExportTicket,
  ReminderPresets,
  Reminders,
  ReminderTimeline,
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
  Meal,
  MealInput,
  MealKind,
  MealRecorded,
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
  | 'stepup_pending'
  | 'rate_limited'
  | 'server';

/** A problem document mapped to a typed error; screens map codes, never message texts (8.3). */
export class ApiError extends Error {
  constructor(
    readonly code: ApiErrorCode,
    readonly reference?: string,
    readonly fields: readonly FieldError[] = [],
    /** Seconds until the request may be repeated, for `rate_limited` (Retry-After). */
    readonly retryAfterSeconds?: number,
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

  // food (MINI_APP 7.8): a write carries the key made when the person acted; an edit names the meal it replaces.
  /** The week ending on `to`; without a range, the last seven days ending today in the account zone. */
  nutritionSummary(from?: LocalDate, to?: LocalDate): Promise<NutritionSummary>;
  meals(from: LocalDate, to: LocalDate): Promise<readonly Meal[]>;
  addMeal(input: MealInput, idempotencyKey: string): Promise<MealRecorded>;
  removeMeal(id: string): Promise<NutritionDay>;
  recentMeals(mealType: MealKind): Promise<readonly string[]>;
  nutritionSettings(): Promise<NutritionSettings>;
  saveNutritionSettings(planned: readonly MealKind[], version: number): Promise<NutritionSettings>;

  // reminders (ADR-023): a write carries the key made when the person acted.
  reminders(): Promise<Reminders>;
  reminderPresets(): Promise<ReminderPresets>;
  turnOnReminder(presetId: string, idempotencyKey: string): Promise<Reminders>;
  turnOffReminder(kind: string): Promise<Reminders>;
  pauseReminders(hours: number, idempotencyKey: string): Promise<Reminders>;
  saveDayProfile(input: DayProfileInput, version: number): Promise<DayProfile>;
  reminderTimeline(): Promise<ReminderTimeline>;

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
  /** The medication's schedules, the ended ones included. */
  schedules(medicationId: string): Promise<readonly Schedule[]>;
  schedule(id: string): Promise<Schedule>;
  /** An edit is a new revision on `revision`, the one the screen loaded (API-17). */
  reviseSchedule(id: string, draft: ScheduleDraft, revision: number): Promise<Schedule>;

  insightsOverview(rangeDays: InsightRange): Promise<InsightsOverview>;
  /** One calendar month; absent is the current month. */
  insightMonth(month?: string): Promise<InsightMonth>;
  /** The patterns of the last 28 days. */
  insightList(): Promise<InsightList>;

  // exports (MINI_APP S-14): a request is confirmed in the chat before anything is made (MA-STEP-1).
  exports(): Promise<readonly ExportItem[]>;
  exportItem(id: string): Promise<ExportItem>;
  requestExport(request: ExportRequest, idempotencyKey: string): Promise<ExportItem>;
  downloadTicket(id: string): Promise<ExportTicket>;
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
