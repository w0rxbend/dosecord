import type {
  HydrationDay,
  InsightRange,
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
  | 'not_found'
  | 'validation'
  | 'version_conflict'
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
  addHydration(amountMl: number, idempotencyKey: string): Promise<HydrationDay>;

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

export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
