// The mock's occurrence state machine for one local day. The server owns the real lifecycle
// (ADR-012); this is just enough of it for the Today screen to be exercised.
import type {
  DayLineMark,
  LocalDate,
  Occurrence,
  OccurrenceAction,
  OccurrenceActionResult,
  TodayView,
} from '../api/types';
import { OCCURRENCE_SEEDS, SNOOZE_CHOICES_MINUTES, type OccurrenceSeed } from './data';
import { addDays, formatDate, toTime } from './dates';

const LATE_AFTER_MINUTES = 60;
const MISSED_AFTER_MINUTES = 240;

type Source = 'chat' | 'app';

interface OccurrenceRecord {
  readonly seed: OccurrenceSeed;
  resolution?: { kind: 'taken' | 'skipped'; atMinute: number; source: Source };
  snoozedUntil?: number;
}

function stateOf(record: OccurrenceRecord, now: number): Pick<Occurrence, 'state' | 'resolvedTime' | 'snoozedUntil' | 'dueForMinutes'> {
  const { seed, resolution, snoozedUntil } = record;
  if (resolution?.kind === 'skipped') return { state: 'skipped' };
  if (resolution) {
    const late = resolution.atMinute - seed.scheduledMinute > LATE_AFTER_MINUTES;
    return { state: late ? 'taken_late' : 'taken', resolvedTime: toTime(resolution.atMinute) };
  }
  if (snoozedUntil !== undefined && snoozedUntil > now) return { state: 'snoozed', snoozedUntil: toTime(snoozedUntil) };
  const waited = now - seed.scheduledMinute;
  if (waited < 0) return { state: 'pending' };
  return waited > MISSED_AFTER_MINUTES ? { state: 'missed' } : { state: 'due', dueForMinutes: waited };
}

function toOccurrence(record: OccurrenceRecord, now: number): Occurrence {
  return {
    id: record.seed.id,
    medicationName: record.seed.medicationName,
    doseText: record.seed.doseText,
    scheduledTime: toTime(record.seed.scheduledMinute),
    snoozeChoicesMinutes: SNOOZE_CHOICES_MINUTES,
    ...stateOf(record, now),
  };
}

export class MockDay {
  private readonly records: OccurrenceRecord[];

  constructor(withOccurrences: boolean) {
    const seeds = withOccurrences ? OCCURRENCE_SEEDS : [];
    this.records = seeds.map((seed) => ({
      seed,
      resolution: seed.resolution && { ...seed.resolution, source: 'app' as const },
    }));
  }

  private find(id: string): OccurrenceRecord | undefined {
    return this.records.find((record) => record.seed.id === id);
  }

  dueMedicationIds(now: number): Set<string> {
    const due = this.records.filter((record) => stateOf(record, now).state === 'due');
    return new Set(due.map((record) => record.seed.medicationId));
  }

  /** What chat would do; lets tests and the dev frame show MA-TODAY-3. */
  resolveInChat(id: string, now: number): void {
    const record = this.find(id);
    if (record) record.resolution = { kind: 'taken', atMinute: now, source: 'chat' };
  }

  act(id: string, action: OccurrenceAction, now: number): OccurrenceActionResult | null {
    const record = this.find(id);
    if (!record) return null;
    const resolvedIn = record.resolution?.source;
    if (action.kind === 'undo') {
      record.resolution = undefined;
      record.snoozedUntil = undefined;
    } else if (resolvedIn) {
      return { occurrence: toOccurrence(record, now), alreadyResolvedIn: resolvedIn };
    } else if (action.kind === 'snoozed') {
      record.snoozedUntil = now + action.minutes;
    } else {
      record.resolution = { kind: action.kind, atMinute: now, source: 'app' };
    }
    return { occurrence: toOccurrence(record, now) };
  }

  view(today: LocalDate, now: number): TodayView {
    const doseMarks = this.records.map((record): DayLineMark => ({
      minute: record.resolution?.kind === 'taken' ? record.resolution.atMinute : record.seed.scheduledMinute,
      kind: 'dose',
      state: stateOf(record, now).state,
    }));
    return {
      ...frame(today, today),
      nowMinute: now,
      occurrences: this.records.map((record) => toOccurrence(record, now)),
      dayLine: doseMarks,
    };
  }
}

function frame(date: LocalDate, today: LocalDate) {
  return {
    date,
    dateLabel: formatDate(date, { weekday: 'long', day: 'numeric', month: 'long' }),
    isToday: date === today,
    readOnly: date > today,
    previousDate: addDays(date, -1),
    nextDate: addDays(date, 1),
  };
}

/** Another day than today: the plan for a future day, a settled record for a past one. */
export function otherDay(date: LocalDate, today: LocalDate, withOccurrences: boolean): TodayView {
  const future = date > today;
  const seeds = withOccurrences ? OCCURRENCE_SEEDS : [];
  const occurrences = seeds.map((seed): Occurrence => ({
    id: `${seed.id}-${date}`,
    medicationName: seed.medicationName,
    doseText: seed.doseText,
    scheduledTime: toTime(seed.scheduledMinute),
    snoozeChoicesMinutes: SNOOZE_CHOICES_MINUTES,
    ...(future ? { state: 'pending' } : { state: 'taken', resolvedTime: toTime(seed.scheduledMinute + 4) }),
  }));
  return {
    ...frame(date, today),
    occurrences,
    dayLine: seeds.map((seed) => ({ minute: seed.scheduledMinute, kind: 'dose', state: future ? 'pending' : 'taken' })),
  };
}
