// In-memory backend for browser development and tests. It must not ship (MA-TEST-2).
import { ApiError, type ApiClient, type FieldError } from '../api/client';
import type { Medication, MedicationDraft, OccurrenceActionResult, Schedule, ScheduleDraft } from '../api/types';
import { parseDecimal, isPositive } from '../forms/decimal';
import { MEDICATION_OPTIONS, SCHEDULE_PATTERNS, WEEKDAYS, seedMedications, toSummary, type MedicationRecord } from './data';
import { toLocalDate } from './dates';
import { MockDay, otherDay } from './day';
import { buildOverview } from './insights';
import { buildPreview } from './preview';

export const MOCK_BACKEND_MARKER = 'dosegram-mock-backend';
const ZONE_LABEL = 'Europe/Kyiv';

export type MockScenario = 'typical' | 'empty' | 'error';

export interface MockBackendOptions {
  readonly now?: () => Date;
  readonly scenario?: MockScenario;
  readonly latencyMs?: number;
}

function draftErrors(draft: MedicationDraft, others: readonly Medication[]): FieldError[] {
  const errors: FieldError[] = [];
  const name = draft.name.trim().toLowerCase();
  if (name === '') errors.push({ field: 'name', code: 'name_required' });
  else if ([...name].length > MEDICATION_OPTIONS.limits.nameMax) errors.push({ field: 'name', code: 'name_too_long' });
  else if (others.some((other) => other.status !== 'archived' && other.name.toLowerCase() === name)) {
    errors.push({ field: 'name', code: 'name_taken' });
  }
  const dose = parseDecimal(draft.doseAmount, { integerDigits: 4, fractionDigits: 3 });
  if (dose === null || !isPositive(dose)) errors.push({ field: 'doseAmount', code: 'dose_invalid' });
  return errors;
}

export class MockBackend implements ApiClient {
  readonly marker = MOCK_BACKEND_MARKER;
  scenario: MockScenario;
  /** Every call, by resource name, in order. Tests assert on it. */
  readonly calls: string[] = [];

  private readonly now: () => Date;
  private readonly latencyMs: number;
  private records: MedicationRecord[] = [];
  private day = new MockDay(true);
  private readonly schedules: Schedule[] = [];
  private readonly answered = new Map<string, OccurrenceActionResult>();
  private nextId = 1;

  constructor(options: MockBackendOptions = {}) {
    this.now = options.now ?? (() => new Date());
    this.latencyMs = options.latencyMs ?? 0;
    this.scenario = options.scenario ?? 'typical';
    this.reset();
  }

  /** Reloads the seed data for the current scenario. */
  reset(): void {
    const empty = this.scenario === 'empty';
    this.records = empty ? [] : seedMedications();
    this.day = new MockDay(!empty);
  }

  resolveInChat(occurrenceId: string): void {
    this.day.resolveInChat(occurrenceId, this.minute());
  }

  private minute(): number {
    const now = this.now();
    return now.getHours() * 60 + now.getMinutes();
  }

  private localToday(): string {
    return toLocalDate(this.now());
  }

  private async respond<T>(resource: string, produce: () => T): Promise<T> {
    this.calls.push(resource);
    if (this.latencyMs > 0) await new Promise((resolve) => setTimeout(resolve, this.latencyMs));
    if (this.scenario === 'error') throw new ApiError('server', 'MOCK-0001');
    return produce();
  }

  private find(id: string): MedicationRecord {
    const record = this.records.find((candidate) => candidate.medication.id === id);
    if (!record) throw new ApiError('not_found', 'MOCK-0404');
    return record;
  }

  private checked(draft: MedicationDraft, exceptId?: string): void {
    const others = this.records.map((record) => record.medication).filter((other) => other.id !== exceptId);
    const errors = draftErrors(draft, others);
    if (errors.length > 0) throw new ApiError('validation', 'MOCK-0422', errors);
  }

  today: ApiClient['today'] = (date) =>
    this.respond('today', () => {
      const today = this.localToday();
      const withOccurrences = this.scenario !== 'empty';
      return date === undefined || date === today
        ? this.day.view(today, this.minute())
        : otherDay(date, today, withOccurrences);
    });

  actOnOccurrence: ApiClient['actOnOccurrence'] = (id, action, key) =>
    this.respond('occurrences/actions', () => {
      const replay = this.answered.get(key);
      if (replay) return replay;
      const result = this.day.act(id, action, this.minute());
      if (!result) throw new ApiError('not_found', 'MOCK-0404');
      this.answered.set(key, result);
      return result;
    });

  addHydration: ApiClient['addHydration'] = (amountMl) =>
    this.respond('hydration/entries', () => this.day.addWater(amountMl, this.minute()));

  medications: ApiClient['medications'] = () =>
    this.respond('medications', () => {
      const due = this.day.dueMedicationIds(this.minute());
      return this.records.map((record) => toSummary(record, due.has(record.medication.id)));
    });

  medication: ApiClient['medication'] = (id) => this.respond('medications/{id}', () => this.find(id).medication);

  medicationOptions: ApiClient['medicationOptions'] = () => this.respond('medication-options', () => MEDICATION_OPTIONS);

  createMedication: ApiClient['createMedication'] = (draft) =>
    this.respond('medications:create', () => {
      this.checked(draft);
      const medication: Medication = { ...draft, id: `med-new-${this.nextId++}`, status: 'active', version: 1 };
      this.records.push({ medication, scheduleSummary: 'No schedule' });
      return medication;
    });

  updateMedication: ApiClient['updateMedication'] = (id, draft, version) =>
    this.respond('medications/{id}:update', () => {
      const record = this.find(id);
      if (record.medication.version !== version) throw new ApiError('version_conflict', 'MOCK-0412');
      this.checked(draft, id);
      record.medication = { ...record.medication, ...draft, version: version + 1 };
      return record.medication;
    });

  scheduleOptions: ApiClient['scheduleOptions'] = () =>
    this.respond('schedule-options', () => ({
      patterns: SCHEDULE_PATTERNS,
      weekdays: WEEKDAYS,
      maxRows: 12,
      today: this.localToday(),
      perTimeDose: true,
    }));

  previewSchedule: ApiClient['previewSchedule'] = (medicationId, draft) =>
    this.respond('schedule-previews', () => this.preview(medicationId, draft));

  createSchedule: ApiClient['createSchedule'] = (medicationId, draft) =>
    this.respond('medications/{id}/schedules:create', () => {
      const record = this.find(medicationId);
      record.scheduleSummary = this.preview(medicationId, draft).summary.replace(/, from .*$/, '');
      const schedule: Schedule = { ...draft, id: `sch-${this.nextId++}`, medicationId, revision: 1 };
      this.schedules.push(schedule);
      return schedule;
    });

  insightsOverview: ApiClient['insightsOverview'] = (rangeDays) =>
    this.respond('insights/overview', () =>
      buildOverview(rangeDays, this.localToday(), this.day.hydration(), this.scenario !== 'empty'),
    );

  private preview(medicationId: string, draft: ScheduleDraft) {
    const { doseUnit } = this.find(medicationId).medication;
    const preview = buildPreview(draft, { today: this.localToday(), doseUnit, zoneLabel: ZONE_LABEL });
    if (draft.pattern !== 'as_needed' && preview.days.length === 0) {
      throw new ApiError('validation', 'MOCK-0422', [{ field: 'schedule', code: 'no_occurrences' }]);
    }
    return preview;
  }
}
