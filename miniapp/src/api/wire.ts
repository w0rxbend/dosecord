// Between the generated OpenAPI types and the types the screens use. The server holds less than
// the screens can show (no form, strength or look of a medication; one dose per schedule), so
// the option resources tell the forms which fields exist, and nothing a person typed is dropped.
import { en } from '../i18n/en';
import type { FieldError } from './client';
import type { components } from './schema';
import type {
  Medication,
  MedicationDraft,
  MedicationOptions,
  MedicationSummary,
  Occurrence,
  Schedule,
  ScheduleDraft,
  ScheduleOptions,
  SchedulePreview,
  TodayView,
} from './types';

export type Wire = components['schemas'];

const ISO_WEEK = [1, 2, 3, 4, 5, 6, 7];

function minuteOf(time: string): number {
  const [hours = '0', minutes = '0'] = time.split(':');
  return Number(hours) * 60 + Number(minutes);
}

function toOccurrence(wire: Wire['DoseOccurrence'], nowMinute: number | undefined): Occurrence {
  const due = wire.state === 'due' && nowMinute !== undefined;
  return {
    id: wire.id,
    medicationName: wire.medicationName,
    doseText: wire.doseText ?? '',
    scheduledTime: wire.scheduledTime,
    state: wire.state,
    resolvedTime: wire.resolvedTime,
    snoozedUntil: wire.snoozedUntil,
    dueForMinutes: due ? Math.max(0, nowMinute - minuteOf(wire.scheduledTime)) : undefined,
    snoozeChoicesMinutes: [],
  };
}

/** The server has no action route yet, so a day is shown and doses are answered in the chat. */
export function toTodayView(wire: Wire['DayView']): TodayView {
  const occurrences = (wire.occurrences ?? []).map((occurrence) => toOccurrence(occurrence, wire.nowMinute));
  return {
    date: wire.date,
    dateLabel: wire.dateLabel,
    isToday: wire.isToday,
    readOnly: true,
    answerInChat: true,
    previousDate: wire.previousDate,
    nextDate: wire.nextDate,
    nowMinute: wire.nowMinute,
    occurrences,
    dayLine: occurrences.map((occurrence) => ({
      minute: minuteOf(occurrence.scheduledTime),
      kind: 'dose' as const,
      state: occurrence.state,
    })),
  };
}

export function toMedicationSummary(wire: Wire['MedicationSummary']): MedicationSummary {
  return {
    id: wire.id,
    name: wire.name,
    status: wire.status,
    detailText: wire.doseText ?? '',
    scheduleSummary: wire.scheduleSummary,
    hasDueOccurrence: false,
    colourTag: 'none',
    shapeTag: 'none',
  };
}

export function toMedication(wire: Wire['Medication']): Medication {
  return {
    id: wire.id,
    status: wire.status,
    version: wire.version,
    name: wire.name,
    form: 'other',
    strengthValue: null,
    strengthUnit: null,
    doseAmount: wire.doseAmount ?? '',
    doseUnit: wire.doseUnit ?? '',
    colourTag: 'none',
    shapeTag: 'none',
    notes: wire.instructions ?? '',
    showNameInReminders: true,
  };
}

/** Server validation entries, `member.code`, as the field and code the forms already show. */
const FIELD_ERRORS: Readonly<Record<string, FieldError>> = {
  'name.required': { field: 'name', code: 'name_required' },
  'name.invalid': { field: 'name', code: 'name_required' },
  'name.too_long': { field: 'name', code: 'name_too_long' },
  'name.duplicate': { field: 'name', code: 'name_taken' },
  'doseAmount.invalid': { field: 'doseAmount', code: 'dose_invalid' },
  'instructions.too_long': { field: 'notes', code: 'notes_too_long' },
  'times.required': { field: 'rows', code: 'times_required' },
  'times.invalid': { field: 'rows', code: 'time_invalid' },
  'times.duplicate': { field: 'rows', code: 'time_duplicate' },
  'endDate.before_start': { field: 'endDate', code: 'end_before_start' },
  'medication.schedule_exists': { field: 'schedule', code: 'schedule_exists' },
};

/** An entry without a mapping keeps its member and code; the form then shows the general text. */
export function toFieldError(wire: Wire['FieldError']): FieldError {
  return FIELD_ERRORS[`${wire.field}.${wire.code}`] ?? { field: wire.field, code: wire.code };
}

const present = (text: string): string | undefined => (text === '' ? undefined : text);

export function toMedicationInput(draft: MedicationDraft): Wire['MedicationInput'] {
  return {
    name: draft.name,
    doseAmount: present(draft.doseAmount),
    doseUnit: present(draft.doseUnit),
    instructions: present(draft.notes),
  };
}

/** Empty choice lists hide the fields the server does not keep. */
export function toMedicationOptions(wire: Wire['MedicationOptions']): MedicationOptions {
  return {
    forms: [],
    strengthUnits: [],
    doseUnits: (wire.doseUnits ?? []).map((unit) => ({ value: unit, label: unit })),
    colourTags: [],
    shapeTags: [],
    limits: { nameMax: wire.limits.medicationNameMax, notesMax: wire.limits.instructionsMax },
    reminderNameToggle: false,
  };
}

/** The server's `fixed_times` covers both "every day" and "certain days" of the builder. */
export function toScheduleOptions(wire: Wire['ScheduleOptions']): ScheduleOptions {
  const offered = wire.patterns ?? [];
  const patterns: ScheduleOptions['patterns'][number][] = [];
  if (offered.includes('fixed_times')) {
    patterns.push({ value: 'fixed_times', label: en.schedule.patterns.fixed_times });
    patterns.push({ value: 'weekly', label: en.schedule.patterns.weekly });
  }
  if (offered.includes('as_needed')) patterns.push({ value: 'as_needed', label: en.schedule.patterns.as_needed });
  return {
    patterns,
    weekdays: ISO_WEEK.map((day, index) => ({ value: day, label: en.schedule.weekdayNames[index] ?? '' })),
    maxRows: wire.timesMax,
    today: wire.today,
    perTimeDose: false,
  };
}

export function toScheduleDraft(draft: ScheduleDraft): Wire['ScheduleDraft'] {
  if (draft.pattern === 'as_needed') return { pattern: 'as_needed' };
  return {
    pattern: 'fixed_times',
    times: draft.rows.map((row) => row.time),
    weekdays: draft.pattern === 'weekly' ? [...draft.weekdays] : [],
    startDate: draft.startDate,
    endDate: draft.endDate ?? undefined,
  };
}

export function toSchedulePreview(wire: Wire['SchedulePreview']): SchedulePreview {
  return {
    summary: wire.summary,
    zoneLabel: wire.timeZone,
    days: (wire.days ?? []).map((day) => ({
      date: day.date,
      label: day.label,
      occurrences: (day.occurrences ?? []).map((occurrence) => ({
        time: occurrence.time,
        doseText: '',
        note: occurrence.note,
      })),
    })),
    moreCount: 0,
  };
}

export function toSchedule(wire: Wire['Schedule']): Schedule {
  const weekdays = wire.weekdays ?? [];
  const everyDay = weekdays.length === ISO_WEEK.length;
  const timed = everyDay ? 'fixed_times' : 'weekly';
  return {
    id: wire.id,
    medicationId: wire.medicationId,
    revision: wire.revision,
    pattern: wire.pattern === 'as_needed' ? 'as_needed' : timed,
    rows: (wire.times ?? []).map((time) => ({ time, dose: '' })),
    weekdays: everyDay ? [] : weekdays,
    startDate: wire.startDate,
    endDate: wire.endDate ?? null,
  };
}
