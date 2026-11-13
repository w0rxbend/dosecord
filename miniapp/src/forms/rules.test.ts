import { describe, expect, it } from 'vitest';
import { parseDecimal } from './decimal';
import { normaliseTime, toScheduleDraft, validateSchedule, type ScheduleForm } from './schedule-rules';

const LIMITS = { integerDigits: 4, fractionDigits: 3 };

describe('parseDecimal', () => {
  it.each([
    ['1', '1'],
    ['1,5', '1.5'],
    ['1.50', '1.5'],
    [' 2.25 ', '2.25'],
    ['007', '7'],
    ['0.125', '0.125'],
    ['0', '0'],
  ])('reads %j as %s', (text, canonical) => {
    expect(parseDecimal(text, LIMITS)).toBe(canonical);
  });

  it.each(['', 'abc', '1.', '.5', '1.2.3', '-1', '1e3', '12345', '1.2345', '1 000'])('rejects %j', (text) => {
    expect(parseDecimal(text, LIMITS)).toBeNull();
  });
});

describe('normaliseTime', () => {
  it.each([
    ['08:00', '08:00'],
    ['8:05', '08:05'],
    ['23:59', '23:59'],
  ])('reads %j as %s', (text, time) => {
    expect(normaliseTime(text)).toBe(time);
  });

  it.each(['', '24:00', '12:60', '8', '08.00', '8:5'])('rejects %j', (text) => {
    expect(normaliseTime(text)).toBeNull();
  });
});

const form = (changes: Partial<ScheduleForm> = {}): ScheduleForm => ({
  pattern: 'fixed_times',
  rows: [
    { key: 0, time: '08:00', dose: '1' },
    { key: 1, time: '21:00', dose: '1' },
  ],
  weekdays: [],
  startDate: '2026-11-12',
  endDate: '',
  ...changes,
});

describe('validateSchedule', () => {
  it('accepts a daily schedule with two distinct times', () => {
    expect(validateSchedule(form())).toEqual([]);
  });

  it('needs at least one time', () => {
    expect(validateSchedule(form({ rows: [] }))).toEqual([{ field: 'rows', code: 'times_required' }]);
  });

  it('reports a repeated time on the later row, however it was typed', () => {
    const rows = [
      { key: 0, time: '08:00', dose: '1' },
      { key: 1, time: '8:00', dose: '1' },
    ];

    expect(validateSchedule(form({ rows }))).toEqual([{ field: 'rows.1.time', code: 'time_duplicate' }]);
  });

  it('reports an unreadable time and a dose that is not above zero on their own fields', () => {
    const rows = [{ key: 0, time: '', dose: '0' }];

    expect(validateSchedule(form({ rows }))).toEqual([
      { field: 'rows.0.time', code: 'time_invalid' },
      { field: 'rows.0.dose', code: 'dose_invalid' },
    ]);
  });

  it('needs a weekday for the weekly pattern only', () => {
    expect(validateSchedule(form({ pattern: 'weekly' }))).toEqual([{ field: 'weekdays', code: 'weekday_required' }]);
    expect(validateSchedule(form({ pattern: 'weekly', weekdays: [1] }))).toEqual([]);
  });

  it('rejects an end date before the start date', () => {
    expect(validateSchedule(form({ endDate: '2026-11-11' }))).toEqual([{ field: 'endDate', code: 'end_before_start' }]);
    expect(validateSchedule(form({ endDate: '2026-11-12' }))).toEqual([]);
  });

  it('asks nothing of an as-needed schedule', () => {
    expect(validateSchedule(form({ pattern: 'as_needed', rows: [] }))).toEqual([]);
  });
});

describe('toScheduleDraft', () => {
  it('normalises times and doses and drops what the pattern does not use', () => {
    const rows = [{ key: 0, time: '8:00', dose: '1,50' }];

    expect(toScheduleDraft(form({ rows, weekdays: [5, 1] }))).toEqual({
      pattern: 'fixed_times',
      rows: [{ time: '08:00', dose: '1.5' }],
      weekdays: [],
      startDate: '2026-11-12',
      endDate: null,
    });
    expect(toScheduleDraft(form({ pattern: 'weekly', weekdays: [5, 1] })).weekdays).toEqual([1, 5]);
    expect(toScheduleDraft(form({ pattern: 'as_needed' })).rows).toEqual([]);
  });
});
