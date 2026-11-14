import { describe, expect, it } from 'vitest';
import { withPending, type WaterView } from './water';

const view: WaterView = {
  day: { date: '2026-11-12', totalMl: 0, entries: 0, text: '0 ml' },
  entries: [],
  presetsMl: [250],
};

describe('pending water rows', () => {
  it('two taps on the same amount in the same minute are two rows with their own keys (MA-HYD-1)', () => {
    const once = withPending(view, 250, '08:12', 'key-1');
    const twice = withPending(once, 250, '08:12', 'key-2');

    expect(twice.entries).toHaveLength(2);
    expect(new Set(twice.entries.map((entry) => entry.id)).size).toBe(2);
    expect(twice.day.totalMl).toBe(500);
  });
});
