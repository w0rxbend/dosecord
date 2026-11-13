// Stands in for insights/overview: synthetic dose history, the mock's water and mood records,
// descriptive sentences, laid-out scenes. Counts and durations only: no score, no rating word,
// no comparison with other people (MA-INS-2).
import type { InsightBlock, InsightRange, InsightsOverview, LocalDate } from '../api/types';
import { formatDate } from './dates';
import { doseCalendar, lastDays, outcomeBar, outcomeLabels, seeded, waterBars, type DoseDay, type DoseOutcomes } from './scenes';
import type { MockTracking } from './tracking';

const litres = (ml: number): string => `${(ml / 1000).toFixed(1)} L`;

export function doseHistory(dates: readonly LocalDate[]): DoseDay[] {
  return dates.map((date) => {
    // Seeded by the date, so a day has the same record in every view.
    const roll = seeded(Number(date.replaceAll('-', '')))();
    return { date, scheduled: 4, taken: roll < 0.7 ? 4 : roll < 0.92 ? 3 : 2 };
  });
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

function hydrationBlock(tracking: MockTracking, dates: readonly LocalDate[], rangeDays: InsightRange): InsightBlock {
  const days = dates.map((date) => ({ date, totalMl: tracking.waterDay(date).totalMl }));
  const recorded = days.filter((day) => day.totalMl > 0);
  const today = tracking.waterDay(dates.at(-1)!);
  const average = recorded.reduce((sum, day) => sum + day.totalMl, 0) / Math.max(1, recorded.length);
  const goal = today.goalMl;
  const firstGap = [...days].reverse().findIndex((day) => day.totalMl === 0);
  return {
    id: 'hydration',
    title: 'Water',
    lines: [
      `${litres(average)} a day on average`,
      ...(goal === undefined ? [] : [`Goal reached on ${recorded.filter((day) => day.totalMl >= goal).length} of ${days.length} days.`]),
      `Water logged ${firstGap === -1 ? days.length : firstGap} days in a row.`,
      `Previous ${rangeDays} days: ${litres(average - 100)} a day on average.`,
    ],
    charts: [waterBars(days, goal)],
    vessel:
      goal === undefined
        ? undefined
        : {
            valueMl: today.totalMl,
            goalMl: goal,
            label: `Today: ${litres(today.totalMl)} of ${litres(goal)}, ${Math.round((today.totalMl / goal) * 100)} percent of your goal.`,
          },
  };
}

function moodBlock(tracking: MockTracking, dates: readonly LocalDate[]): InsightBlock {
  const chart = tracking.moodChart(dates);
  const checkins = tracking.moodCheckins(dates[0]!, dates.at(-1)!);
  const days = new Set(checkins.map((checkin) => checkin.date)).size;
  return chart
    ? {
        id: 'mood',
        title: 'Mood',
        lines: [`${checkins.length} check-ins on ${days} of ${dates.length} days`, `${days} of ${dates.length} days have data`],
        charts: [chart],
      }
    : emptyBlock('mood', 'Mood');
}

function emptyBlock(id: InsightBlock['id'], title: string): InsightBlock {
  return { id, title, lines: [], noDataText: 'No data in this period', charts: [] };
}

export function buildOverview(
  rangeDays: InsightRange,
  today: LocalDate,
  tracking: MockTracking,
  hasHistory: boolean,
): InsightsOverview {
  const dates = lastDays(today, rangeDays);
  const short = { day: 'numeric', month: 'short' } as const;
  const blocks: InsightBlock[] = [];
  if (tracking.isOn('medication')) {
    blocks.push(hasHistory ? medicationBlock(doseHistory(dates), rangeDays) : emptyBlock('medication', 'Medication'));
  }
  if (tracking.isOn('hydration')) {
    blocks.push(hasHistory ? hydrationBlock(tracking, dates, rangeDays) : emptyBlock('hydration', 'Water'));
  }
  if (tracking.isOn('mood')) blocks.push(moodBlock(tracking, dates));
  return {
    rangeDays,
    rangeLabel: `${formatDate(dates[0]!, short)} to ${formatDate(today, short)}`,
    ranges: [7, 30, 90],
    blocks,
    notice: blocks.length === 0 ? 'Statistics appear here for the records you turned on in the chat.' : undefined,
  };
}
