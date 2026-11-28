import type { LocalDate, SleepEpisodeInput, SleepKind } from '../../api/types';
import { localInstant } from '../../forms/instant';

export interface NightDraft {
  readonly kind: SleepKind;
  readonly onsetDate: LocalDate;
  readonly onsetTime: string;
  readonly wakeDate: LocalDate;
  readonly wakeTime: string;
}

/**
 * The wall-clock times as instants with the device offset. The server stores instants, so a
 * duration is the difference of instants, also on a clock-change night (MA-SLEEP-6).
 */
export function toEpisodeInput(draft: NightDraft): SleepEpisodeInput {
  return {
    kind: draft.kind,
    onsetAt: localInstant(draft.onsetDate, draft.onsetTime),
    wakeAt: localInstant(draft.wakeDate, draft.wakeTime),
  };
}

const pad = (value: number): string => String(value).padStart(2, '0');

/** The device's own date of `now`, and its time to the 5 minutes before, as the form's fields hold them. */
function deviceWallClock(now: Date): { date: LocalDate; time: string } {
  const date = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
  const minutes = now.getMinutes();
  return { date, time: `${pad(now.getHours())}:${pad(minutes - (minutes % 5))}` };
}

/**
 * A night that ended this morning: from 23:00 the evening before to 07:00. Before 07:00 on `today` it ends now (to the
 * 5 minutes before), so the night the form starts from is never in the future (supervisor decision, 2026-11-28).
 */
export function lastNightDraft(today: LocalDate, now: Date = new Date()): NightDraft {
  const evening = new Date(`${today}T12:00:00Z`);
  evening.setUTCDate(evening.getUTCDate() - 1);
  const device = deviceWallClock(now);
  const wakeTime = device.date === today && device.time < '07:00' ? device.time : '07:00';
  return { kind: 'night', onsetDate: evening.toISOString().slice(0, 10), onsetTime: '23:00', wakeDate: today, wakeTime };
}
