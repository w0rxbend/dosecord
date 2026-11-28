import type { InsightRange } from '../../api/types';

/** The Year tab beside the periods (supervisor decision, 2026-11-28): the year of /stats year. */
export const YEAR = 'year';
/** The last period or Year tab, kept per device as an interface preference (MA-INS-1, MINI_APP 6.6). */
export const RANGE_KEY = 'ui_v1_insights_range';
export const DEFAULT_RANGE: InsightRange = 30;

export type Shown = InsightRange | typeof YEAR;

/** The stored choice, or undefined when the value is not a period or tab this build knows. */
export function shownOf(stored: string | undefined): Shown | undefined {
  if (stored === YEAR) return YEAR;
  const days = Number(stored);
  return days === 7 || days === 30 || days === 90 ? days : undefined;
}

/** The period the overview is read for: the stored period, else the default (the Year tab reads its own data). */
export function overviewDays(shown: Shown | undefined): InsightRange {
  return shown === undefined || shown === YEAR ? DEFAULT_RANGE : shown;
}
