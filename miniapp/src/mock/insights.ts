// Stands in for insights/overview: synthetic history, descriptive sentences, laid-out scenes.
// Counts and durations only: no score, no rating word, no comparison with other people (MA-INS-2).
import type { HydrationDay, InsightBlock, InsightRange, InsightsOverview, LocalDate } from '../api/types';
import { formatDate } from './dates';
import {
  doseCalendar,
  lastDays,
  outcomeBar,
  outcomeLabels,
  seeded,
  waterBars,
  type DoseDay,
  type DoseOutcomes,
  type WaterDay,
} from './scenes';

const litres = (ml: number): string => `${(ml / 1000).toFixed(1)} L`;

function doseHistory(dates: readonly LocalDate[]): DoseDay[] {
  const random = seeded(7);
  return dates.map((date) => {
    const roll = random();
    return { date, scheduled: 4, taken: roll < 0.7 ? 4 : roll < 0.92 ? 3 : 2 };
  });
}

function waterHistory(dates: readonly LocalDate[], todayMl: number): WaterDay[] {
  const random = seeded(11);
  return dates.map((date, index) => ({
    date,
    totalMl: index === dates.length - 1 ? todayMl : 900 + Math.round(random() * 16) * 100,
  }));
}

function outcomesOf(days: readonly DoseDay[]): DoseOutcomes {
  const scheduled = days.reduce((sum, day) => sum + day.scheduled, 0);
  const taken = days.reduce((sum, day) => sum + day.taken, 0);
  const open = scheduled - taken;
  const late = Math.round(taken * 0.08);
  const skipped = Math.ceil(open / 2);
  const noRecord = open > 4 ? 1 : 0;
  return { onTime: taken - late, late, skipped, notConfirmed: open - skipped - noRecord, noRecord };
}

function medicationBlock(days: readonly DoseDay[], rangeDays: InsightRange): InsightBlock {
  const outcomes = outcomesOf(days);
  const total = days.reduce((sum, day) => sum + day.scheduled, 0);
  const counts = outcomeLabels(outcomes).map(([label, count]) => `${count} ${label}`);
  return {
    id: 'medication',
    title: 'Medication',
    lines: [
      `${total} scheduled doses`,
      `${counts.join(', ')}.`,
      `Previous ${rangeDays} days: ${outcomes.onTime - 6} on time of ${total - 2}.`,
    ],
    charts: [outcomeBar(outcomes), doseCalendar(days)],
  };
}

/** How many days up to and including today have at least one entry. */
function daysInARow(days: readonly WaterDay[]): number {
  const firstGap = [...days].reverse().findIndex((day) => day.totalMl === 0);
  return firstGap === -1 ? days.length : firstGap;
}

function hydrationBlock(days: readonly WaterDay[], today: HydrationDay, rangeDays: InsightRange): InsightBlock {
  const average = days.reduce((sum, day) => sum + day.totalMl, 0) / days.length;
  const reached = days.filter((day) => day.totalMl >= today.goalMl).length;
  return {
    id: 'hydration',
    title: 'Water',
    lines: [
      `${litres(average)} a day on average`,
      `Goal reached on ${reached} of ${days.length} days.`,
      `Water logged ${daysInARow(days)} days in a row.`,
      `Previous ${rangeDays} days: ${litres(average - 100)} a day on average.`,
    ],
    charts: [waterBars(days, today.goalMl)],
    vessel: {
      valueMl: today.totalMl,
      goalMl: today.goalMl,
      label: `Today: ${litres(today.totalMl)} of ${litres(today.goalMl)}, ${Math.round((today.totalMl / today.goalMl) * 100)} percent of your goal.`,
    },
  };
}

function emptyBlock(id: InsightBlock['id'], title: string): InsightBlock {
  return { id, title, lines: [], noDataText: 'No data in this period', charts: [] };
}

export function buildOverview(
  rangeDays: InsightRange,
  today: LocalDate,
  hydration: HydrationDay,
  hasHistory: boolean,
): InsightsOverview {
  const dates = lastDays(today, rangeDays);
  const short = { day: 'numeric', month: 'short' } as const;
  return {
    rangeDays,
    rangeLabel: `${formatDate(dates[0]!, short)} to ${formatDate(today, short)}`,
    ranges: [7, 30, 90],
    blocks: hasHistory
      ? [
          medicationBlock(doseHistory(dates), rangeDays),
          hydrationBlock(waterHistory(dates, hydration.totalMl), hydration, rangeDays),
        ]
      : [emptyBlock('medication', 'Medication'), emptyBlock('hydration', 'Water')],
  };
}
