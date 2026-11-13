import { describe, expect, it } from 'vitest';
import type { ScheduleDraft } from '../api/types';
import { buildPreview } from './preview';

// 2026-11-12 is a Thursday.
const CONTEXT = { today: '2026-11-12', doseUnit: 'tablet', zoneLabel: 'Europe/Kyiv' };

const DAILY: ScheduleDraft = {
  pattern: 'fixed_times',
  rows: [
    { time: '21:00', dose: '1' },
    { time: '08:00', dose: '1' },
  ],
  weekdays: [],
  startDate: '2026-11-12',
  endDate: null,
};

const countOccurrences = (draft: ScheduleDraft): number =>
  buildPreview(draft, CONTEXT).days.reduce((total, day) => total + day.occurrences.length, 0);

describe('buildPreview', () => {
  it('lists exactly 14 occurrences over seven days for every day at 08:00 and 21:00', () => {
    const preview = buildPreview(DAILY, CONTEXT);

    expect(preview.days).toHaveLength(7);
    expect(countOccurrences(DAILY)).toBe(14);
    expect(preview.days[0]?.label).toBe('Thu 12 Nov');
    expect(preview.days[6]?.label).toBe('Wed 18 Nov');
  });

  it('orders the times of a day and carries the dose with its unit', () => {
    const [first] = buildPreview(DAILY, CONTEXT).days;

    expect(first?.occurrences).toEqual([
      { time: '08:00', doseText: '1 tablet' },
      { time: '21:00', doseText: '1 tablet' },
    ]);
  });

  it('keeps only the chosen weekdays for a weekly schedule', () => {
    const weekly: ScheduleDraft = { ...DAILY, pattern: 'weekly', weekdays: [1, 3, 5] };

    const labels = buildPreview(weekly, CONTEXT).days.map((day) => day.label);

    expect(labels).toEqual(['Fri 13 Nov', 'Mon 16 Nov', 'Wed 18 Nov']);
  });

  it('starts the window at a start date in the future', () => {
    const later: ScheduleDraft = { ...DAILY, startDate: '2026-11-20' };

    expect(buildPreview(later, CONTEXT).days[0]?.date).toBe('2026-11-20');
  });

  it('stops at the end date', () => {
    const short: ScheduleDraft = { ...DAILY, endDate: '2026-11-13' };

    expect(buildPreview(short, CONTEXT).days.map((day) => day.date)).toEqual(['2026-11-12', '2026-11-13']);
  });

  it('has no occurrences and says so for an as-needed schedule', () => {
    const preview = buildPreview({ ...DAILY, pattern: 'as_needed', rows: [] }, CONTEXT);

    expect(preview.days).toEqual([]);
    expect(preview.summary).toBe('Only when needed. No reminders.');
  });

  it('truncates after 100 occurrences and counts the rest', () => {
    const rows = Array.from({ length: 16 }, (_, hour) => ({ time: `${String(hour).padStart(2, '0')}:00`, dose: '1' }));

    const preview = buildPreview({ ...DAILY, rows }, CONTEXT);

    expect(preview.days.reduce((total, day) => total + day.occurrences.length, 0)).toBe(100);
    expect(preview.moreCount).toBe(12);
  });

  it('writes the summary sentence from the draft', () => {
    expect(buildPreview(DAILY, CONTEXT).summary).toBe('Every day at 08:00 and 21:00, from 12 Nov, no end date.');
  });
});
