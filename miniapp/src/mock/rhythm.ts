// Stands in for insights/rhythm (MINI_APP 7.11, the Rhythm view): the mock's water entries by the hour
// of the local day over the last 30 days, as /stats rhythm prints them. An area shows its rhythm once it
// has records on 14 of the 30 days; fewer show those days.
import type { InsightRhythm, InsightRhythmArea, LocalDate } from '../api/types';
import { addDays, formatDate } from './dates';
import type { MockTracking } from './tracking';

const WINDOW_DAYS = 30;
const GATE = 14;
const PARTS = [
  ['morning', 6],
  ['afternoon', 12],
  ['evening', 18],
  ['night', 0],
] as const;

const shortDate = (date: LocalDate): string => formatDate(date, { day: 'numeric', month: 'short' });
const clock = (hour: number): string => `${String(hour % 24).padStart(2, '0')}:00`;
const counted = (n: number, one: string, other: string): string => `${n} ${n === 1 ? one : other}`;

function waterArea(hours: number[], recordedDays: number): InsightRhythmArea {
  const days = counted(recordedDays, 'day', 'days');
  const base = { id: 'hydration' as const, title: 'Water', counts: 'Water entries', recordedDays, days };
  if (recordedDays < GATE) {
    return { ...base, text: recordedDays > 0 ? `Water: records on ${days}` : 'Water: —' };
  }
  const total = hours.reduce((sum, n) => sum + n, 0);
  const peak = hours.indexOf(Math.max(...hours));
  const parts = PARTS.map(([name, from]) => `${name} ${hours.slice(from, from + 6).reduce((sum, n) => sum + n, 0)}`);
  const summary = `${counted(total, 'entry', 'entries')}, most often ${clock(peak)} to ${clock(peak + 1)} · ${parts.join(', ')}`;
  return { ...base, text: `Water (${days}): ${summary}`, summary, hours };
}

export function buildRhythm(today: LocalDate, tracking: MockTracking): InsightRhythm {
  const start = addDays(today, 1 - WINDOW_DAYS);
  const entries = tracking.isOn('hydration') ? tracking.waterEntries(start, today) : [];
  const hours = Array.from({ length: 24 }, () => 0);
  for (const entry of entries) hours[Number(entry.time.slice(0, 2))]! += 1;
  const recordedDays = new Set(entries.map((entry) => entry.date)).size;
  const areas = tracking.isOn('hydration') ? [waterArea(hours, recordedDays)] : [];
  const summary = `${shortDate(start)} to ${shortDate(today)}: days with records ${recordedDays} of ${WINDOW_DAYS}`;
  const gateNote = `An area shows its rhythm once it has records on ${GATE} of the ${WINDOW_DAYS} days; fewer show those days.`;
  const clockNote = 'Times are the local clock times of your records.';
  const none = 'Nothing was recorded in the last 30 days.';
  return {
    summary,
    gateNote: areas.length > 0 ? gateNote : undefined,
    clockNote,
    areas,
    lines: [
      'Rhythm of the day · last 30 days · Europe/Kyiv',
      summary,
      ...(recordedDays > 0 && areas.length > 0
        ? [gateNote, clockNote, ...areas.map((area) => area.text), 'These are the times of your own records. They set no target and show no cause.']
        : [none]),
    ],
    notice:
      areas.length === 0
        ? 'Statistics appear here for the records you turned on in the chat.'
        : recordedDays > 0
          ? undefined
          : none,
  };
}
