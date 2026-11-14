// The rule table of the schedule builder (MINI_APP 7.5). Codes are shared with the server.
import type { FieldError } from '../api/client';
import type { LocalDate, ScheduleDraft, SchedulePattern } from '../api/types';
import { isPositive, parseDecimal } from './decimal';

export interface ScheduleRowForm {
  /** Stable key for the list; not sent to the server. */
  key: number;
  time: string;
  dose: string;
}

export interface ScheduleForm {
  pattern: SchedulePattern;
  rows: ScheduleRowForm[];
  weekdays: number[];
  startDate: LocalDate;
  endDate: LocalDate | '';
  /** every_n_days and cycle (ROADMAP G9.8); whole days as typed. */
  everyDays?: string;
  onDays?: string;
  offDays?: string;
  /** as_needed: the user's own limits; empty means none. */
  maxPerDay?: string;
  minGapMinutes?: string;
}

const PERIOD_MAX = 365;
const MAX_PER_DAY = 24;
const MIN_GAP_MAX = 1440;

/** A whole number in [min, max] as typed, or null. */
function wholeNumber(text: string | undefined, min: number, max: number): number | null {
  const trimmed = (text ?? '').trim();
  if (!/^\d{1,4}$/.test(trimmed)) return null;
  const value = Number(trimmed);
  return value >= min && value <= max ? value : null;
}

function periodErrors(form: ScheduleForm): FieldError[] {
  const fields: (keyof ScheduleForm)[] = form.pattern === 'every_n_days' ? ['everyDays'] : form.pattern === 'cycle' ? ['onDays', 'offDays'] : [];
  return fields
    .filter((field) => wholeNumber(form[field] as string | undefined, 1, PERIOD_MAX) === null)
    .map((field) => ({ field, code: 'period_invalid' }));
}

function limitErrors(form: ScheduleForm): FieldError[] {
  const errors: FieldError[] = [];
  if ((form.maxPerDay ?? '').trim() !== '' && wholeNumber(form.maxPerDay, 1, MAX_PER_DAY) === null)
    errors.push({ field: 'maxPerDay', code: 'out_of_range' });
  if ((form.minGapMinutes ?? '').trim() !== '' && wholeNumber(form.minGapMinutes, 0, MIN_GAP_MAX) === null)
    errors.push({ field: 'minGapMinutes', code: 'out_of_range' });
  return errors;
}

const DOSE = { integerDigits: 4, fractionDigits: 3 };
const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Accepts H:MM and HH:MM and normalises to HH:MM (MA-SCHED-4); null when not a clock time. */
export function normaliseTime(text: string): string | null {
  const match = /^(\d{1,2}):(\d{2})$/.exec(text.trim());
  if (!match) return null;
  const hours = Number(match[1]);
  const minutes = Number(match[2]);
  if (hours > 23 || minutes > 59) return null;
  return `${String(hours).padStart(2, '0')}:${match[2]}`;
}

export const rowField = (index: number, part: 'time' | 'dose'): string => `rows.${index}.${part}`;

function rowErrors(rows: readonly ScheduleRowForm[], perTimeDose: boolean): FieldError[] {
  if (rows.length === 0) return [{ field: 'rows', code: 'times_required' }];
  const seen = new Set<string>();
  return rows.flatMap((row, index) => {
    const errors: FieldError[] = [];
    const time = normaliseTime(row.time);
    if (time === null) errors.push({ field: rowField(index, 'time'), code: 'time_invalid' });
    else if (seen.has(time)) errors.push({ field: rowField(index, 'time'), code: 'time_duplicate' });
    else seen.add(time);

    const dose = parseDecimal(row.dose, DOSE);
    if (perTimeDose && (dose === null || !isPositive(dose))) errors.push({ field: rowField(index, 'dose'), code: 'dose_invalid' });
    return errors;
  });
}

function dateErrors(form: ScheduleForm): FieldError[] {
  if (!DATE.test(form.startDate)) return [{ field: 'startDate', code: 'date_invalid' }];
  if (form.endDate === '') return [];
  if (!DATE.test(form.endDate)) return [{ field: 'endDate', code: 'date_invalid' }];
  // ISO local dates order the same way as text.
  return form.endDate < form.startDate ? [{ field: 'endDate', code: 'end_before_start' }] : [];
}

/** `perTimeDose` is false when the server keeps one dose per schedule: the row dose is then not asked. */
export function validateSchedule(form: ScheduleForm, perTimeDose = true): FieldError[] {
  if (form.pattern === 'as_needed') return limitErrors(form);
  const weekdayErrors =
    form.pattern === 'weekly' && form.weekdays.length === 0
      ? [{ field: 'weekdays', code: 'weekday_required' }]
      : [];
  return [...weekdayErrors, ...periodErrors(form), ...rowErrors(form.rows, perTimeDose), ...dateErrors(form)];
}

/** Call only for a form that validateSchedule accepts. */
export function toScheduleDraft(form: ScheduleForm): ScheduleDraft {
  const timed = form.pattern !== 'as_needed';
  return {
    pattern: form.pattern,
    rows: timed
      ? form.rows.map((row) => ({
          time: normaliseTime(row.time) ?? row.time,
          dose: parseDecimal(row.dose, DOSE) ?? row.dose,
        }))
      : [],
    weekdays: form.pattern === 'weekly' ? [...form.weekdays].sort((a, b) => a - b) : [],
    startDate: form.startDate,
    endDate: form.endDate === '' ? null : form.endDate,
    ...periodOf(form),
  };
}

/** The pattern's own numbers, only for that pattern. */
function periodOf(form: ScheduleForm): Partial<ScheduleDraft> {
  switch (form.pattern) {
    case 'every_n_days':
      return { everyDays: wholeNumber(form.everyDays, 1, PERIOD_MAX) ?? undefined };
    case 'cycle':
      return {
        onDays: wholeNumber(form.onDays, 1, PERIOD_MAX) ?? undefined,
        offDays: wholeNumber(form.offDays, 1, PERIOD_MAX) ?? undefined,
      };
    case 'as_needed': {
      const maxPerDay = wholeNumber(form.maxPerDay, 1, MAX_PER_DAY);
      const minGapMinutes = wholeNumber(form.minGapMinutes, 0, MIN_GAP_MAX);
      return {
        ...(maxPerDay === null ? {} : { maxPerDay }),
        ...(minGapMinutes === null ? {} : { minGapMinutes }),
      };
    }
    default:
      return {};
  }
}
