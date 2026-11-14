// In-memory backend for browser development and tests. It must not ship (MA-TEST-2).
import { ApiError, type ApiClient, type FieldError } from '../api/client';
import type { Medication, MedicationDraft, OccurrenceActionResult, Schedule, ScheduleDraft } from '../api/types';
import { parseDecimal, isPositive } from '../forms/decimal';
import {
  MEDICATION_OPTIONS,
  SCHEDULE_PATTERNS,
  WEEKDAYS,
  seedMedications,
  seedSchedules,
  toSummary,
  type MedicationRecord,
} from './data';
import { toLocalDate } from './dates';
import { MockDay, otherDay } from './day';
import { buildInsightList, buildMonth, buildOverview, doseHistory } from './insights';
import { buildPreview } from './preview';
import { MockReminders } from './reminders';
import { MockTracking } from './tracking';

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
  /** Water, mood, calendar, settings and consents. Tests reach in, for example to withdraw a consent. */
  tracking!: MockTracking;
  /** The reminders hub, the presets and the day profile (ADR-023). */
  reminderState!: MockReminders;
  private storedSchedules: Schedule[] = [];
  private readonly answered = new Map<string, OccurrenceActionResult>();
  private readonly replies = new Map<string, unknown>();
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
    this.storedSchedules = empty ? [] : seedSchedules(this.localToday());
    this.day = new MockDay(!empty);
    this.tracking = new MockTracking(() => this.localToday(), () => this.minute(), !empty);
    this.reminderState = new MockReminders();
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
      if (!this.tracking.isOn('medication')) throw new ApiError('consent_required', 'MOCK-0403');
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

  sleepSummary: ApiClient['sleepSummary'] = () => this.respond('sleep/summary', () => this.tracking.sleep.summary());

  sleepEpisodes: ApiClient['sleepEpisodes'] = (from, to) =>
    this.respond('sleep/episodes', () => this.tracking.sleep.episodesIn(from, to));

  addSleep: ApiClient['addSleep'] = (input, key) =>
    this.respond('sleep/episodes:create', () => this.once(key, () => this.tracking.sleep.add(input)));

  removeSleep: ApiClient['removeSleep'] = (id) =>
    this.respond('sleep/episodes/{id}:delete', () => this.tracking.sleep.remove(id));

  sleepCheckIn: ApiClient['sleepCheckIn'] = (kind, key) =>
    this.respond('sleep/checkins:create', () => this.once(key, () => this.tracking.sleep.checkIn(kind)));

  sleepSettings: ApiClient['sleepSettings'] = () => this.respond('sleep/settings', () => this.tracking.sleep.settings());

  saveSleepSettings: ApiClient['saveSleepSettings'] = (goalMinutes, version) =>
    this.respond('sleep/settings:update', () => this.tracking.sleep.saveSettings(goalMinutes, version));

  reminders: ApiClient['reminders'] = () => this.respond('reminders', () => this.reminderState.view());

  reminderPresets: ApiClient['reminderPresets'] = () =>
    this.respond('reminders/presets', () => this.reminderState.presets());

  turnOnReminder: ApiClient['turnOnReminder'] = (presetId, key) =>
    this.respond('reminders/plans:create', () => this.once(key, () => this.reminderState.turnOn(presetId)));

  turnOffReminder: ApiClient['turnOffReminder'] = (kind) =>
    this.respond('reminders/plans/{kind}:delete', () => this.reminderState.turnOff(kind));

  pauseReminders: ApiClient['pauseReminders'] = (hours, key) =>
    this.respond('reminders/pause:create', () => this.once(key, () => this.reminderState.pause(hours)));

  saveDayProfile: ApiClient['saveDayProfile'] = (input, version) =>
    this.respond('reminders/day:update', () => this.reminderState.saveDay(input, version));

  reminderTimeline: ApiClient['reminderTimeline'] = () =>
    this.respond('reminders/timeline', () => this.reminderState.timeline());

  waterSummary: ApiClient['waterSummary'] = () => this.respond('hydration/summary', () => this.tracking.waterSummary());

  waterEntries: ApiClient['waterEntries'] = (from, to) =>
    this.respond('hydration/entries', () => this.tracking.waterEntries(from, to));

  addWater: ApiClient['addWater'] = (amountMl, key) =>
    this.respond('hydration/entries:create', () => this.once(key, () => this.tracking.addWater(amountMl)));

  removeWater: ApiClient['removeWater'] = (id) =>
    this.respond('hydration/entries/{id}:delete', () => this.tracking.removeWater(id));

  waterSettings: ApiClient['waterSettings'] = () => this.respond('hydration/settings', () => this.tracking.waterSettings());

  saveWaterSettings: ApiClient['saveWaterSettings'] = (input, version) =>
    this.respond('hydration/settings:update', () => this.tracking.saveWaterSettings(input, version));

  moodScale: ApiClient['moodScale'] = () => this.respond('mood/scale', () => this.tracking.moodScale());

  blobStates: ApiClient['blobStates'] = () => this.respond('mood/blob-states', () => this.tracking.blobStates());

  moodSummary: ApiClient['moodSummary'] = () => this.respond('mood/summary', () => this.tracking.moodSummary());

  moodCheckins: ApiClient['moodCheckins'] = (from, to) =>
    this.respond('mood/checkins', () => this.tracking.moodCheckins(from, to));

  checkIn: ApiClient['checkIn'] = (input, key) =>
    this.respond('mood/checkins:create', () => this.once(key, () => this.tracking.checkIn(input)));

  removeCheckin: ApiClient['removeCheckin'] = (id) =>
    this.respond('mood/checkins/{id}:delete', () => this.tracking.removeCheckin(id));

  calendar: ApiClient['calendar'] = (domain, month) =>
    this.respond('insights/calendar', () =>
      this.tracking.calendar(domain, month, (date) => {
        const [day] = doseHistory([date]);
        return this.scenario === 'empty' || !day ? [0, 0] : [day.taken, day.scheduled];
      }),
    );

  settings: ApiClient['settings'] = () => this.respond('settings', () => this.tracking.settings());

  saveSettings: ApiClient['saveSettings'] = (input, version) =>
    this.respond('settings:update', () => this.tracking.saveSettings(input, version));

  notificationSettings: ApiClient['notificationSettings'] = () =>
    this.respond('notification-settings', () => this.tracking.notificationSettings());

  saveNotificationSettings: ApiClient['saveNotificationSettings'] = (input, version) =>
    this.respond('notification-settings:update', () => this.tracking.saveNotificationSettings(input, version));

  timeZones: ApiClient['timeZones'] = () => this.respond('time-zones', () => this.tracking.timeZones());

  previewTimeZone: ApiClient['previewTimeZone'] = (timeZone) =>
    this.respond('time-zone-previews', () => this.tracking.previewTimeZone(timeZone));

  consents: ApiClient['consents'] = () => this.respond('consents', () => this.tracking.consents());

  /** A repeated idempotency key answers the first result (MA-HYD-1). */
  private once<T>(key: string, run: () => T): T {
    const replay = this.replies.get(key);
    if (replay !== undefined) return replay as T;
    const result = run();
    this.replies.set(key, result);
    return result;
  }

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
      const schedule: Schedule = {
        ...draft,
        id: `sch-${this.nextId++}`,
        medicationId,
        status: 'active',
        revision: 1,
        summary: record.scheduleSummary,
      };
      this.storedSchedules.push(schedule);
      return schedule;
    });

  schedules: ApiClient['schedules'] = (medicationId) =>
    this.respond('medications/{id}/schedules', () => this.storedSchedules.filter((s) => s.medicationId === medicationId));

  schedule: ApiClient['schedule'] = (id) => this.respond('schedules/{id}', () => this.findSchedule(id));

  reviseSchedule: ApiClient['reviseSchedule'] = (id, draft, revision) =>
    this.respond('schedules/{id}/revisions', () => {
      const current = this.findSchedule(id);
      if (current.revision !== revision) throw new ApiError('version_conflict', 'MOCK-0412');
      const summary = this.preview(current.medicationId, draft).summary.replace(/, from .*$/, '');
      this.find(current.medicationId).scheduleSummary = summary;
      const revised: Schedule = { ...current, ...draft, revision: revision + 1, summary };
      this.storedSchedules = this.storedSchedules.map((s) => (s.id === id ? revised : s));
      return revised;
    });

  private findSchedule(id: string): Schedule {
    const found = this.storedSchedules.find((s) => s.id === id);
    if (!found) throw new ApiError('not_found', 'MOCK-0404');
    return found;
  }

  insightsOverview: ApiClient['insightsOverview'] = (rangeDays) =>
    this.respond('insights/overview', () =>
      buildOverview(rangeDays, this.localToday(), this.tracking, this.scenario !== 'empty'),
    );

  insightMonth: ApiClient['insightMonth'] = (month) =>
    this.respond('insights/month', () =>
      buildMonth(
        month,
        this.localToday(),
        this.tracking,
        this.records.map(({ medication }) => ({
          id: medication.id,
          name: medication.name,
          archived: medication.status === 'archived',
        })),
        this.scenario !== 'empty',
      ),
    );

  insightList: ApiClient['insightList'] = () =>
    this.respond('insights/correlations', () => buildInsightList(this.tracking, this.scenario !== 'empty'));

  private preview(medicationId: string, draft: ScheduleDraft) {
    const { doseUnit } = this.find(medicationId).medication;
    const preview = buildPreview(draft, { today: this.localToday(), doseUnit, zoneLabel: ZONE_LABEL });
    if (draft.pattern !== 'as_needed' && preview.days.length === 0) {
      throw new ApiError('validation', 'MOCK-0422', [{ field: 'schedule', code: 'no_occurrences' }]);
    }
    return preview;
  }
}
