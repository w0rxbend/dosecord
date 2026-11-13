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

function rowErrors(rows: readonly ScheduleRowForm[]): FieldError[] {
  if (rows.length === 0) return [{ field: 'rows', code: 'times_required' }];
  const seen = new Set<string>();
  return rows.flatMap((row, index) => {
    const errors: FieldError[] = [];
    const time = normaliseTime(row.time);
    if (time === null) errors.push({ field: rowField(index, 'time'), code: 'time_invalid' });
    else if (seen.has(time)) errors.push({ field: rowField(index, 'time'), code: 'time_duplicate' });
    else seen.add(time);

    const dose = parseDecimal(row.dose, DOSE);
    if (dose === null || !isPositive(dose)) errors.push({ field: rowField(index, 'dose'), code: 'dose_invalid' });
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

export function validateSchedule(form: ScheduleForm): FieldError[] {
  if (form.pattern === 'as_needed') return [];
  const weekdayErrors =
    form.pattern === 'weekly' && form.weekdays.length === 0
      ? [{ field: 'weekdays', code: 'weekday_required' }]
      : [];
  return [...weekdayErrors, ...rowErrors(form.rows), ...dateErrors(form)];
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
  };
}
