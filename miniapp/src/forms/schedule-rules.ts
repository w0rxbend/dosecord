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

/** One group of days with its own times (ROADMAP G9.8). */
export interface DayGroupForm {
  key: number;
  weekdays: number[];
  rows: ScheduleRowForm[];
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
  /** as_needed: the user's own limits; empty means none. interval_fixed_start: the most doses a day. */
  maxPerDay?: string;
  minGapMinutes?: string;
  /** every_n_weeks: whole weeks as typed. */
  everyWeeks?: string;
  /** The stored anchor of a periodic rule, kept through an edit; absent counts from the start date. */
  anchorDate?: LocalDate;
  /** interval_fixed_start: the first dose of each day and whole hours between doses. */
  startTime?: string;
  intervalHours?: string;
  /** day_groups: the groups of days, each with its own times. */
  groups?: DayGroupForm[];
}

const PERIOD_MAX = 365;
const WEEKS_MAX = 52;
const INTERVAL_HOURS_MAX = 12;
const INTERVAL_PER_DAY_MAX = 12;
const GROUPS_MAX = 7;
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

export const rowField = (index: number, part: 'time' | 'dose', prefix = 'rows'): string => `${prefix}.${index}.${part}`;

/** The field prefix of the rows of day group `index`. */
export const groupRows = (index: number): string => `groups.${index}.rows`;

function rowErrors(rows: readonly ScheduleRowForm[], perTimeDose: boolean, prefix = 'rows'): FieldError[] {
  if (rows.length === 0) return [{ field: prefix, code: 'times_required' }];
  const seen = new Set<string>();
  return rows.flatMap((row, index) => {
    const errors: FieldError[] = [];
    const time = normaliseTime(row.time);
    if (time === null) errors.push({ field: rowField(index, 'time', prefix), code: 'time_invalid' });
    else if (seen.has(time)) errors.push({ field: rowField(index, 'time', prefix), code: 'time_duplicate' });
    else seen.add(time);

    const dose = parseDecimal(row.dose, DOSE);
    if (perTimeDose && (dose === null || !isPositive(dose)))
      errors.push({ field: rowField(index, 'dose', prefix), code: 'dose_invalid' });
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

/** Every N weeks: its days and whole weeks from 1 to 52. */
function weeklyPeriodErrors(form: ScheduleForm): FieldError[] {
  if (form.pattern !== 'every_n_weeks') return [];
  return wholeNumber(form.everyWeeks, 1, WEEKS_MAX) === null ? [{ field: 'everyWeeks', code: 'weeks_invalid' }] : [];
}

/** Every few hours from a fixed start: the first dose, whole hours and the most doses of a day. */
function intervalErrors(form: ScheduleForm): FieldError[] {
  return [
    ...(normaliseTime(form.startTime ?? '') === null ? [{ field: 'startTime', code: 'time_invalid' }] : []),
    ...(wholeNumber(form.intervalHours, 1, INTERVAL_HOURS_MAX) === null ? [{ field: 'intervalHours', code: 'hours_invalid' }] : []),
    ...(wholeNumber(form.maxPerDay, 1, INTERVAL_PER_DAY_MAX) === null ? [{ field: 'maxPerDay', code: 'per_day_invalid' }] : []),
  ];
}

/** Day groups: each has days and times, and a day is in one group only. */
function groupErrors(form: ScheduleForm, perTimeDose: boolean): FieldError[] {
  const groups = form.groups ?? [];
  const days = groups.flatMap((group) => group.weekdays);
  return [
    ...(groups.length === 0 || groups.length > GROUPS_MAX ? [{ field: 'groups', code: 'out_of_range' }] : []),
    ...(new Set(days).size !== days.length ? [{ field: 'groups', code: 'weekday_overlap' }] : []),
    ...groups.flatMap((group, index) => [
      ...(group.weekdays.length === 0 ? [{ field: `groups.${index}.weekdays`, code: 'weekday_required' }] : []),
      ...rowErrors(group.rows, perTimeDose, groupRows(index)),
    ]),
  ];
}

/** `perTimeDose` is false when the server keeps one dose per schedule: the row dose is then not asked. */
export function validateSchedule(form: ScheduleForm, perTimeDose = true): FieldError[] {
  if (form.pattern === 'as_needed') return limitErrors(form);
  if (form.pattern === 'interval_fixed_start') return [...intervalErrors(form), ...dateErrors(form)];
  if (form.pattern === 'day_groups') return [...groupErrors(form, perTimeDose), ...dateErrors(form)];
  const weekdayErrors =
    (form.pattern === 'weekly' || form.pattern === 'every_n_weeks') && form.weekdays.length === 0
      ? [{ field: 'weekdays', code: 'weekday_required' }]
      : [];
  return [
    ...weekdayErrors,
    ...periodErrors(form),
    ...weeklyPeriodErrors(form),
    ...rowErrors(form.rows, perTimeDose),
    ...dateErrors(form),
  ];
}

/** Whether the builder can show a stored schedule as it is; one it cannot is shown read-only, never simplified. */
export function builderCanExpress(schedule: ScheduleDraft): boolean {
  if (schedule.pattern === 'interval_fixed_start') return (schedule.intervalMinutes ?? 0) % 60 === 0;
  return true;
}

const toRows = (rows: readonly ScheduleRowForm[]) =>
  rows.map((row) => ({ time: normaliseTime(row.time) ?? row.time, dose: parseDecimal(row.dose, DOSE) ?? row.dose }));

const sortedDays = (days: readonly number[]) => [...days].sort((a, b) => a - b);

const DAYS_OF: ReadonlySet<ScheduleForm['pattern']> = new Set(['weekly', 'every_n_weeks', 'interval_fixed_start']);

/** Call only for a form that validateSchedule accepts. */
export function toScheduleDraft(form: ScheduleForm): ScheduleDraft {
  const timed = form.pattern !== 'as_needed' && form.pattern !== 'interval_fixed_start' && form.pattern !== 'day_groups';
  return {
    pattern: form.pattern,
    rows: timed ? toRows(form.rows) : [],
    weekdays: DAYS_OF.has(form.pattern) ? sortedDays(form.weekdays) : [],
    startDate: form.startDate,
    endDate: form.endDate === '' ? null : form.endDate,
    ...periodOf(form),
  };
}

/** A stored anchor is sent back unchanged, so an edit keeps the rhythm the schedule already had. */
const anchorOf = (form: ScheduleForm): Partial<ScheduleDraft> => (form.anchorDate ? { anchorDate: form.anchorDate } : {});

/** The pattern's own numbers, only for that pattern. */
function periodOf(form: ScheduleForm): Partial<ScheduleDraft> {
  switch (form.pattern) {
    case 'every_n_days':
      return { everyDays: wholeNumber(form.everyDays, 1, PERIOD_MAX) ?? undefined, ...anchorOf(form) };
    case 'cycle':
      return {
        onDays: wholeNumber(form.onDays, 1, PERIOD_MAX) ?? undefined,
        offDays: wholeNumber(form.offDays, 1, PERIOD_MAX) ?? undefined,
        ...anchorOf(form),
      };
    case 'every_n_weeks':
      return { everyWeeks: wholeNumber(form.everyWeeks, 1, WEEKS_MAX) ?? undefined, ...anchorOf(form) };
    case 'interval_fixed_start':
      return {
        startTime: normaliseTime(form.startTime ?? '') ?? undefined,
        intervalMinutes: (wholeNumber(form.intervalHours, 1, INTERVAL_HOURS_MAX) ?? 0) * 60,
        maxPerDay: wholeNumber(form.maxPerDay, 1, INTERVAL_PER_DAY_MAX) ?? undefined,
      };
    case 'day_groups':
      return {
        groups: (form.groups ?? []).map((group) => ({ weekdays: sortedDays(group.weekdays), rows: toRows(group.rows) })),
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
