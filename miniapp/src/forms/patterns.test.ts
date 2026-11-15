import { describe, expect, it } from 'vitest';
import { toSchedule, toScheduleDraft as toWireDraft, toScheduleOptions } from '../api/wire';
import { builderCanExpress, toScheduleDraft, validateSchedule, type ScheduleForm } from './schedule-rules';

// ROADMAP G9.8 and G9.9: every few weeks, every few hours from a fixed start and groups of days in the builder,
// in the server's terms on the wire.

const form = (changes: Partial<ScheduleForm> = {}): ScheduleForm => ({
  pattern: 'every_n_weeks',
  rows: [{ key: 0, time: '09:00', dose: '' }],
  weekdays: [1],
  startDate: '2026-11-16',
  endDate: '',
  everyWeeks: '2',
  startTime: '06:00',
  intervalHours: '6',
  maxPerDay: '4',
  groups: [
    { key: 1, weekdays: [1, 2, 3, 4, 5], rows: [{ key: 2, time: '08:00', dose: '' }] },
    { key: 3, weekdays: [6, 7], rows: [{ key: 4, time: '10:00', dose: '' }] },
  ],
  ...changes,
});

describe('every few weeks', () => {
  it('sends its days, the period and the times', () => {
    expect(validateSchedule(form(), false)).toEqual([]);
    expect(toWireDraft(toScheduleDraft(form()))).toEqual({
      pattern: 'every_n_weeks',
      times: ['09:00'],
      weekdays: [1],
      everyWeeks: 2,
      startDate: '2026-11-16',
    });
  });

  it('needs a day and whole weeks from 1 to 52', () => {
    expect(validateSchedule(form({ weekdays: [], everyWeeks: '53' }), false)).toEqual([
      { field: 'weekdays', code: 'weekday_required' },
      { field: 'everyWeeks', code: 'weeks_invalid' },
    ]);
  });

  it('keeps the stored anchor through an edit', () => {
    expect(toWireDraft(toScheduleDraft(form({ anchorDate: '2026-11-02' })))).toMatchObject({ anchorDate: '2026-11-02' });
  });
});

describe('every few hours from a fixed start', () => {
  it('sends the first dose, the interval in minutes and the most a day; no days means every day', () => {
    const interval = form({ pattern: 'interval_fixed_start', weekdays: [] });
    expect(validateSchedule(interval, false)).toEqual([]);
    expect(toWireDraft(toScheduleDraft(interval))).toEqual({
      pattern: 'interval_fixed_start',
      startTime: '06:00',
      intervalMinutes: 360,
      maxPerDay: 4,
      weekdays: [],
      startDate: '2026-11-16',
    });
  });

  it('needs a clock time, whole hours from 1 to 12 and 1 to 12 a day', () => {
    expect(validateSchedule(form({ pattern: 'interval_fixed_start', startTime: '', intervalHours: '13', maxPerDay: '0' }), false)).toEqual([
      { field: 'startTime', code: 'time_invalid' },
      { field: 'intervalHours', code: 'hours_invalid' },
      { field: 'maxPerDay', code: 'per_day_invalid' },
    ]);
  });

  it('a stored interval of whole hours is shown in the form; one of 90 minutes is shown read-only', () => {
    const stored = toSchedule({
      id: 's1',
      medicationId: 'm1',
      status: 'active',
      revision: 1,
      pattern: 'interval_fixed_start',
      times: [],
      weekdays: [1, 2, 3, 4, 5, 6, 7],
      timeZone: 'UTC',
      startDate: '2026-11-16',
      summary: 'every 6 h from 06:00, at most 4 a day',
      startTime: '06:00',
      intervalMinutes: 360,
      maxPerDay: 4,
    });
    expect(stored).toMatchObject({ pattern: 'interval_fixed_start', startTime: '06:00', intervalMinutes: 360, maxPerDay: 4, weekdays: [] });
    expect(builderCanExpress(stored)).toBe(true);
    expect(builderCanExpress({ ...stored, intervalMinutes: 90 })).toBe(false);
  });
});

describe('groups of days', () => {
  it('sends fixed times with one slot group per group', () => {
    const grouped = form({ pattern: 'day_groups' });
    expect(validateSchedule(grouped, false)).toEqual([]);
    expect(toWireDraft(toScheduleDraft(grouped))).toEqual({
      pattern: 'fixed_times',
      slotGroups: [
        { weekdays: [1, 2, 3, 4, 5], times: ['08:00'] },
        { weekdays: [6, 7], times: ['10:00'] },
      ],
      startDate: '2026-11-16',
    });
  });

  it('a day is in one group only, and each group needs days and times', () => {
    const errors = validateSchedule(
      form({
        pattern: 'day_groups',
        groups: [
          { key: 1, weekdays: [1, 2], rows: [{ key: 2, time: '08:00', dose: '' }] },
          { key: 3, weekdays: [2], rows: [] },
          { key: 4, weekdays: [], rows: [{ key: 5, time: '25:00', dose: '' }] },
        ],
      }),
      false,
    );
    expect(errors).toEqual([
      { field: 'groups', code: 'weekday_overlap' },
      { field: 'groups.1.rows', code: 'times_required' },
      { field: 'groups.2.weekdays', code: 'weekday_required' },
      { field: 'groups.2.rows.0.time', code: 'time_invalid' },
    ]);
  });

  it('a stored schedule of two groups comes back as two groups, never flattened', () => {
    const stored = toSchedule({
      id: 's2',
      medicationId: 'm2',
      status: 'active',
      revision: 1,
      pattern: 'fixed_times',
      times: [],
      weekdays: [],
      timeZone: 'UTC',
      startDate: '2026-11-16',
      summary: 'on weekdays 08:00; on Sat, Sun 10:00',
      slotGroups: [
        { weekdays: [1, 2, 3, 4, 5], times: ['08:00'] },
        { weekdays: [6, 7], times: ['10:00'] },
      ],
    });
    expect(stored.pattern).toBe('day_groups');
    expect(stored.groups?.map((group) => [group.weekdays, group.rows.map((row) => row.time)])).toEqual([
      [[1, 2, 3, 4, 5], ['08:00']],
      [[6, 7], ['10:00']],
    ]);
  });
});

it('offers the new patterns exactly when the server lists them', () => {
  const offered = toScheduleOptions({
    patterns: ['fixed_times', 'as_needed', 'every_n_days', 'cycle', 'every_n_weeks', 'interval_fixed_start'],
    timesMax: 12,
    previewDays: 7,
    timeZone: 'UTC',
    today: '2026-11-16',
  });
  expect(offered.patterns.map((pattern) => pattern.value)).toEqual([
    'fixed_times',
    'weekly',
    'day_groups',
    'every_n_days',
    'every_n_weeks',
    'cycle',
    'interval_fixed_start',
    'as_needed',
  ]);
});
