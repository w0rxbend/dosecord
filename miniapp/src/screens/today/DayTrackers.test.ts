import { render, screen } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import { OFF } from '../../api/types';
import { createHarness } from '../../test/harness';
import DayTrackers from './DayTrackers.svelte';

const entry = { id: 'w-1', date: '2026-11-13', time: '21:36', amountMl: 300, text: '300 ml' };

describe('Day trackers', () => {
  // The day detail of the current date reads entries, not the summary: its water card must not be empty.
  it('lists the entries of today when the day is opened from the calendar', () => {
    render(DayTrackers, {
      props: { tracking: { water: { entries: [entry], presetsMl: [] }, mood: OFF, sleep: OFF }, isToday: true, onAddWater: () => {} },
      context: createHarness().context,
    });

    expect(screen.getByText('Water, 300 ml')).toBeTruthy();
  });
});
