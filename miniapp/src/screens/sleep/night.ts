import type { LocalDate, SleepEpisodeInput, SleepKind } from '../../api/types';

export interface NightDraft {
  readonly kind: SleepKind;
  readonly onsetDate: LocalDate;
  readonly onsetTime: string;
  readonly wakeDate: LocalDate;
  readonly wakeTime: string;
}

const pad = (value: number): string => String(Math.floor(Math.abs(value))).padStart(2, '0');

/** The offset this device has at a wall-clock time, as `+02:00`. */
function offsetAt(date: LocalDate, time: string): string {
  const minutes = -new Date(`${date}T${time}:00`).getTimezoneOffset();
  return `${minutes < 0 ? '-' : '+'}${pad(minutes / 60)}:${pad(minutes % 60)}`;
}

/**
 * The wall-clock times as instants with the device offset. The server stores instants, so a
 * duration is the difference of instants, also on a clock-change night (MA-SLEEP-6).
 */
export function toEpisodeInput(draft: NightDraft): SleepEpisodeInput {
  return {
    kind: draft.kind,
    onsetAt: `${draft.onsetDate}T${draft.onsetTime}:00${offsetAt(draft.onsetDate, draft.onsetTime)}`,
    wakeAt: `${draft.wakeDate}T${draft.wakeTime}:00${offsetAt(draft.wakeDate, draft.wakeTime)}`,
  };
}

/** A night that ended this morning: from 23:00 the evening before to 07:00. */
export function lastNightDraft(today: LocalDate): NightDraft {
  const evening = new Date(`${today}T12:00:00Z`);
  evening.setUTCDate(evening.getUTCDate() - 1);
  return { kind: 'night', onsetDate: evening.toISOString().slice(0, 10), onsetTime: '23:00', wakeDate: today, wakeTime: '07:00' };
}
