import { unlessOff, type ApiClient } from '../../api/client';
import { OFF, type DayLineMark, type MoodCheckin, type MoodDay, type Off, type WaterDay, type WaterEntry } from '../../api/types';
import { moodAssets, type MoodAssets } from '../../state/mood-assets';

/** Water and mood of one local day, next to its doses (MINI_APP 7.1, 7.13). */
export interface DayTracking {
  readonly water: { readonly day?: WaterDay; readonly entries: readonly WaterEntry[]; readonly presetsMl: readonly number[] } | Off;
  readonly mood: { readonly day?: MoodDay; readonly checkins: readonly MoodCheckin[] } | Off;
  readonly assets?: MoodAssets;
}

/**
 * Today reads the summaries, which carry the quick-add amounts and the latest check-in; another
 * day reads its entries. A domain whose consent is missing reads as off.
 */
export async function readTracking(api: ApiClient, date: string | undefined): Promise<DayTracking> {
  const [water, mood] = await Promise.all([readWater(api, date), readMood(api, date)]);
  return { water, mood, assets: mood === OFF ? undefined : await moodAssets(api) };
}

async function readWater(api: ApiClient, date: string | undefined): Promise<DayTracking['water']> {
  if (date !== undefined) {
    const entries = await unlessOff(api.waterEntries(date, date));
    return entries === OFF ? OFF : { entries, presetsMl: [] };
  }
  const summary = await unlessOff(api.waterSummary());
  if (summary === OFF) return OFF;
  const entries = await api.waterEntries(summary.today.date, summary.today.date);
  return { day: summary.today, entries, presetsMl: summary.presetsMl };
}

async function readMood(api: ApiClient, date: string | undefined): Promise<DayTracking['mood']> {
  if (date !== undefined) {
    const checkins = await unlessOff(api.moodCheckins(date, date));
    return checkins === OFF ? OFF : { checkins };
  }
  const summary = await unlessOff(api.moodSummary());
  if (summary === OFF) return OFF;
  return { day: summary.today, checkins: summary.today.latest ? [summary.today.latest] : [] };
}

const minuteOf = (time: string): number => Number(time.slice(0, 2)) * 60 + Number(time.slice(3, 5));

/** Water entries are ticks under the day line, check-ins are marks above it (MINI_APP 5.1). */
export function trackingMarks(tracking: DayTracking | undefined): DayLineMark[] {
  if (!tracking) return [];
  const water = tracking.water === OFF ? [] : tracking.water.entries.map((entry) => ({ minute: minuteOf(entry.time), kind: 'water' as const }));
  const mood = tracking.mood === OFF ? [] : tracking.mood.checkins.map((checkin) => ({ minute: minuteOf(checkin.time), kind: 'mood' as const }));
  return [...water, ...mood];
}
