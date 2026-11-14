import { describe, expect, it } from 'vitest';
import { toSchedule, toScheduleDraft as toWireDraft, toScheduleOptions } from '../api/wire';
import { toScheduleDraft, validateSchedule, type ScheduleForm } from './schedule-rules';

// ROADMAP G9.8 and G9.9: every few days and days on, days off in the builder, and the as-needed limits the user sets.

const form = (changes: Partial<ScheduleForm> = {}): ScheduleForm => ({
  pattern: 'every_n_days',
  rows: [{ key: 0, time: '09:00', dose: '' }],
  weekdays: [],
  startDate: '2026-11-16',
  endDate: '',
  everyDays: '2',
  onDays: '21',
  offDays: '7',
  maxPerDay: '',
  minGapMinutes: '',
  ...changes,
});

describe('periodic patterns', () => {
  it('accepts every other day and sends the period with the times', () => {
    expect(validateSchedule(form(), false)).toEqual([]);
    expect(toWireDraft(toScheduleDraft(form()))).toEqual({
      pattern: 'every_n_days',
      times: ['09:00'],
      everyDays: 2,
      onDays: undefined,
      offDays: undefined,
      startDate: '2026-11-16',
      endDate: undefined,
    });
  });

  it('needs whole days from 1 to 365 on each side of a cycle', () => {
    expect(validateSchedule(form({ pattern: 'cycle', onDays: '0', offDays: '366' }), false)).toEqual([
      { field: 'onDays', code: 'period_invalid' },
      { field: 'offDays', code: 'period_invalid' },
    ]);
    expect(validateSchedule(form({ everyDays: '1.5' }), false)).toEqual([{ field: 'everyDays', code: 'period_invalid' }]);
    expect(toScheduleDraft(form({ pattern: 'cycle' }))).toMatchObject({ pattern: 'cycle', onDays: 21, offDays: 7 });
  });

  it('a stored cycle comes back as a cycle, never as a daily schedule', () => {
    const schedule = toSchedule({
      id: 's1',
      medicationId: 'm1',
      status: 'active',
      revision: 1,
      pattern: 'cycle',
      times: ['08:00'],
      weekdays: [],
      timeZone: 'Europe/Kyiv',
      startDate: '2026-11-16',
      summary: '21 days on, 7 days off from Mon 16 Nov 08:00',
      onDays: 21,
      offDays: 7,
      anchorDate: '2026-11-16',
    });
    expect(schedule).toMatchObject({ pattern: 'cycle', onDays: 21, offDays: 7, weekdays: [] });
  });

  it('offers only the patterns the server lists', () => {
    const offered = toScheduleOptions({
      patterns: ['fixed_times', 'as_needed', 'every_n_days', 'cycle'],
      timesMax: 12,
      previewDays: 7,
      timeZone: 'UTC',
      today: '2026-11-16',
    });
    expect(offered.patterns.map((pattern) => pattern.value)).toEqual(['fixed_times', 'weekly', 'every_n_days', 'cycle', 'as_needed']);
  });
});

describe('as-needed limits', () => {
  it('are optional; when entered they are whole numbers in range and are sent', () => {
    const asNeeded = form({ pattern: 'as_needed', rows: [] });
    expect(validateSchedule(asNeeded)).toEqual([]);
    expect(toWireDraft(toScheduleDraft(asNeeded))).toEqual({ pattern: 'as_needed', maxPerDay: undefined, minGapMinutes: undefined });
    expect(validateSchedule(form({ pattern: 'as_needed', maxPerDay: '0', minGapMinutes: '2000' }))).toEqual([
      { field: 'maxPerDay', code: 'out_of_range' },
      { field: 'minGapMinutes', code: 'out_of_range' },
    ]);
    expect(toWireDraft(toScheduleDraft(form({ pattern: 'as_needed', maxPerDay: '3', minGapMinutes: '240' })))).toEqual({
      pattern: 'as_needed',
      maxPerDay: 3,
      minGapMinutes: 240,
    });
  });

  it("the chat's default of 10 a day without a gap is not shown as a limit", () => {
    const stored = toSchedule({
      id: 's2',
      medicationId: 'm2',
      status: 'active',
      revision: 1,
      pattern: 'as_needed',
      times: [],
      weekdays: [],
      timeZone: 'UTC',
      startDate: '2026-11-16',
      summary: 'as needed',
      maxPerDay: 10,
      minGapMinutes: 0,
    });
    expect(stored.maxPerDay).toBeUndefined();
    expect(stored.minGapMinutes).toBeUndefined();
  });
});
