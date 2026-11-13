// Stands in for the server's schedule-previews resource. The real evaluator, with its
// daylight-saving rule (ADR-011), lives on the server; this one knows calendar days only.
import type { LocalDate, PreviewDay, ScheduleDraft, SchedulePreview } from '../api/types';
import { addDays, formatDate, isoWeekday } from './dates';

const WINDOW_DAYS = 7;
const MAX_OCCURRENCES = 100;
const WEEKDAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

export interface PreviewContext {
  readonly today: LocalDate;
  readonly doseUnit: string;
  readonly zoneLabel: string;
}

function joinWords(words: readonly string[]): string {
  if (words.length <= 1) return words.join('');
  return `${words.slice(0, -1).join(', ')} and ${words.at(-1)}`;
}

function isScheduledOn(draft: ScheduleDraft, date: LocalDate): boolean {
  if (date < draft.startDate || (draft.endDate !== null && date > draft.endDate)) return false;
  return draft.pattern === 'fixed_times' || draft.weekdays.includes(isoWeekday(date));
}

function summarise(draft: ScheduleDraft): string {
  if (draft.pattern === 'as_needed') return 'Only when needed. No reminders.';
  const days =
    draft.pattern === 'fixed_times'
      ? 'Every day'
      : `Every ${joinWords(draft.weekdays.map((day) => WEEKDAY_NAMES[day - 1]!))}`;
  const times = joinWords(draft.rows.map((row) => row.time).sort());
  const start = formatDate(draft.startDate, { day: 'numeric', month: 'short' });
  const end =
    draft.endDate === null
      ? 'no end date'
      : `until ${formatDate(draft.endDate, { day: 'numeric', month: 'short' })}`;
  return `${days} at ${times}, from ${start}, ${end}.`;
}

function previewDay(draft: ScheduleDraft, date: LocalDate, doseUnit: string): PreviewDay {
  const rows = [...draft.rows].sort((a, b) => a.time.localeCompare(b.time));
  return {
    date,
    label: formatDate(date, { weekday: 'short', day: 'numeric', month: 'short' }),
    occurrences: rows.map((row) => ({ time: row.time, doseText: `${row.dose} ${doseUnit}` })),
  };
}

/** Removes occurrences beyond the cap, from the end, and reports how many were removed. */
function truncate(days: readonly PreviewDay[]): { days: PreviewDay[]; moreCount: number } {
  let room = MAX_OCCURRENCES;
  let moreCount = 0;
  const kept: PreviewDay[] = [];
  for (const day of days) {
    const occurrences = day.occurrences.slice(0, room);
    room -= occurrences.length;
    moreCount += day.occurrences.length - occurrences.length;
    if (occurrences.length > 0) kept.push({ ...day, occurrences });
  }
  return { days: kept, moreCount };
}

/** The next seven local days, starting from the later of today and the start date. */
export function buildPreview(draft: ScheduleDraft, context: PreviewContext): SchedulePreview {
  const summary = summarise(draft);
  if (draft.pattern === 'as_needed') {
    return { summary, zoneLabel: context.zoneLabel, days: [], moreCount: 0 };
  }
  const first = draft.startDate > context.today ? draft.startDate : context.today;
  const scheduled = Array.from({ length: WINDOW_DAYS }, (_, offset) => addDays(first, offset))
    .filter((date) => isScheduledOn(draft, date))
    .map((date) => previewDay(draft, date, context.doseUnit));
  return { summary, zoneLabel: context.zoneLabel, ...truncate(scheduled) };
}
