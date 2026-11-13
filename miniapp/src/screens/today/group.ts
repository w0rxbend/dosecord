import type { DoseState, Occurrence, OccurrenceAction } from '../../api/types';

export interface DoseGroups {
  readonly dueNow: readonly Occurrence[];
  readonly later: readonly Occurrence[];
  readonly earlier: readonly Occurrence[];
}

type GroupName = keyof DoseGroups;

/** Earlier doses are collapsed when there are more rows than this (MINI_APP 7.1 behaviour 1). */
export const EARLIER_COLLAPSE_ABOVE = 3;

const GROUP_OF: Readonly<Record<DoseState, GroupName>> = {
  due: 'dueNow',
  pending: 'later',
  snoozed: 'later',
  taken: 'earlier',
  taken_late: 'earlier',
  skipped: 'earlier',
  missed: 'earlier',
  unknown: 'earlier',
};

/** Groups a day's occurrences as Due now, Later and Earlier, each in order of scheduled time. */
export function groupOccurrences(occurrences: readonly Occurrence[]): DoseGroups {
  const sorted = [...occurrences].sort((a, b) => a.scheduledTime.localeCompare(b.scheduledTime));
  const pick = (name: GroupName) => sorted.filter((occurrence) => GROUP_OF[occurrence.state] === name);
  return { dueNow: pick('dueNow'), later: pick('later'), earlier: pick('earlier') };
}

const pad = (value: number): string => String(value).padStart(2, '0');

/**
 * The state shown within 100 ms of the tap, before the server answers (MA-TODAY-2).
 * The server's answer always replaces it (MA-CONC-3).
 */
export function optimistic(occurrence: Occurrence, action: OccurrenceAction, now: Date): Occurrence {
  const minutes = now.getHours() * 60 + now.getMinutes();
  const time = (total: number): string => `${pad(Math.floor(total / 60) % 24)}:${pad(total % 60)}`;
  switch (action.kind) {
    case 'taken':
      return { ...occurrence, state: 'taken', resolvedTime: time(minutes) };
    case 'skipped':
      return { ...occurrence, state: 'skipped' };
    case 'snoozed':
      return { ...occurrence, state: 'snoozed', snoozedUntil: time(minutes + action.minutes) };
    case 'undo':
      return occurrence;
  }
}

export function replaceOccurrence(list: readonly Occurrence[], next: Occurrence): Occurrence[] {
  return list.map((occurrence) => (occurrence.id === next.id ? next : occurrence));
}
