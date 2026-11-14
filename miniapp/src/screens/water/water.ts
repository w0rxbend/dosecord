import type { WaterDay, WaterEntry } from '../../api/types';

/** What the Water screen shows: today, its entries and the quick-add amounts. */
export interface WaterView {
  readonly day: WaterDay;
  readonly entries: readonly WaterEntry[];
  readonly presetsMl: readonly number[];
}

/**
 * The day as the person expects it the moment they tap: the amount added and a pending entry. The
 * server's answer replaces it (MA-CONC-2); the figures in words wait for that answer.
 */
export function withPending(view: WaterView, amountMl: number, time: string, key: string): WaterView {
  const totalMl = view.day.totalMl + amountMl;
  const goalMl = view.day.goalMl;
  return {
    ...view,
    day: {
      ...view.day,
      totalMl,
      entries: view.day.entries + 1,
      percent: goalMl === undefined ? undefined : Math.floor((100 * totalMl) / goalMl),
    },
    // Keyed by the tap's idempotency key: two taps on one amount in one minute are two rows (MA-HYD-1), and the list
    // is keyed by id, where a repeated id fails the whole screen.
    entries: [...view.entries, { id: `pending-${key}`, date: view.day.date, time, amountMl, text: `${amountMl} ml` }],
  };
}

export const isPending = (entry: WaterEntry): boolean => entry.id.startsWith('pending-');

/** The local clock time of the device for the pending row; the server's time replaces it. */
export function clockNow(now: Date = new Date()): string {
  return `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`;
}
