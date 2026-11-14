// Stands in for insights/overview: synthetic dose history, the mock's water and mood records,
// descriptive sentences, laid-out scenes. Counts and durations only: no score, no rating word,
// no comparison with other people (MA-INS-2).
import type {
  InsightBlock,
  InsightList,
  InsightMonth,
  InsightMonthDomain,
  InsightRange,
  InsightsOverview,
  LocalDate,
} from '../api/types';
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

function sleepBlock(tracking: MockTracking, dates: readonly LocalDate[]): InsightBlock {
  const nights = dates.map((date) => ({ date, minutes: tracking.sleep.minutesOn(date) }));
  const logged = nights.filter((night) => night.minutes !== undefined).length;
  return logged > 0
    ? {
        id: 'sleep',
        title: 'Sleep',
        lines: [`${logged} nights logged`, 'your check-ins', `${logged} of ${dates.length} days have data`],
        charts: [tracking.sleep.chart(nights)],
      }
    : emptyBlock('sleep', 'Sleep');
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
  if (tracking.isOn('sleep')) blocks.push(sleepBlock(tracking, dates));
  return {
    rangeDays,
    rangeLabel: `${formatDate(dates[0]!, short)} to ${formatDate(today, short)}`,
    ranges: [7, 30, 90],
    blocks,
    notice: blocks.length === 0 ? 'Statistics appear here for the records you turned on in the chat.' : undefined,
  };
}

// Stands in for insights/month and insights/correlations: the month's figures per domain with
// the gate of 14 days, the medications with their own counts, and a pattern in the server's
// words. Counts only, never a mean of percentages and never a judgement.

const GATE_DAYS = 14;

function monthDates(month: string, today: LocalDate): LocalDate[] {
  const dates: LocalDate[] = [];
  for (let day = 1; day <= 31; day++) {
    const date = `${month}-${String(day).padStart(2, '0')}`;
    if (date > today || new Date(`${date}T12:00:00Z`).getUTCMonth() + 1 !== Number(month.slice(5))) break;
    dates.push(date);
  }
  return dates;
}

function shiftMonth(month: string, by: number): string {
  const [year, value] = month.split('-').map(Number) as [number, number];
  const index = year * 12 + value - 1 + by;
  return `${Math.floor(index / 12)}-${String((index % 12) + 1).padStart(2, '0')}`;
}

function gated(id: InsightMonthDomain['id'], title: string, recorded: number, lines: string[]): InsightMonthDomain {
  return recorded >= GATE_DAYS
    ? { id, title, recordedDays: recorded, meetsGate: true, lines }
    : {
        id,
        title,
        recordedDays: recorded,
        meetsGate: false,
        lines: [],
        gateText: `${title}: ${GATE_DAYS - recorded} more days with records for the month's figures (${recorded} of ${GATE_DAYS})`,
      };
}

export interface MockMedicationName {
  readonly id: string;
  readonly name: string;
  readonly archived: boolean;
}

export function buildMonth(
  month: string | undefined,
  today: LocalDate,
  tracking: MockTracking,
  medications: readonly MockMedicationName[],
  hasHistory: boolean,
): InsightMonth {
  const current = today.slice(0, 7);
  const shown = month ?? current;
  const dates = hasHistory ? monthDates(shown, today) : [];
  const domains: InsightMonthDomain[] = [];
  const doses = doseHistory(dates);
  if (tracking.isOn('medication')) {
    const taken = doses.reduce((sum, day) => sum + day.taken, 0);
    const scheduled = doses.reduce((sum, day) => sum + day.scheduled, 0);
    domains.push(gated('medication', 'Medication', doses.length, [`${taken} of ${scheduled} taken`]));
  }
  if (tracking.isOn('hydration')) {
    const water = dates.filter((date) => tracking.waterDay(date).totalMl > 0).length;
    domains.push(gated('hydration', 'Water', water, [`${water} of ${dates.length} days have data`]));
  }
  const reportable = domains.some((domain) => domain.meetsGate);
  const resolved = doses.length * 2;
  const rows = tracking.isOn('medication')
    ? medications.map((medication, index) => {
        const taken = Math.max(0, resolved - index - 1);
        const percent = resolved >= 5 ? Math.round((100 * taken) / resolved) : undefined;
        return {
          id: medication.id,
          name: medication.name,
          archived: medication.archived,
          line: `${medication.name}: ${taken} of ${resolved} taken${percent === undefined ? '' : ` (${percent}%)`}`,
          taken,
          resolved,
          percent,
          asNeededLogs: 0,
        };
      })
    : [];
  return {
    month: shown,
    monthLabel: formatDate(`${shown}-01`, { month: 'long', year: 'numeric' }),
    previousMonth: shiftMonth(shown, -1),
    nextMonth: shown < current ? shiftMonth(shown, 1) : undefined,
    reportable,
    daysWithRecords: dates.length,
    summary: `${dates.length} days with records`,
    domains,
    medications: rows.filter((row) => row.resolved > 0),
    notice: reportable ? undefined : 'A monthly summary needs one area with records on at least 14 days.',
  };
}

export function buildInsightList(tracking: MockTracking, hasHistory: boolean): InsightList {
  const crossDomain = tracking.isOn('hydration') && tracking.isOn('mood');
  const how = [
    'Period: the last 28 days.',
    'Only days with both records are compared: at least 14 such days and 5 in each group, and the difference holds in both halves of the period and without any single day.',
    'This is a count over your own records. It cannot show a cause.',
  ];
  return {
    windowLabel: '28 days to 12 Nov',
    crossDomain,
    items:
      hasHistory && crossDomain
        ? [
            {
              id: 'IR-04.DailyMoodMean',
              text: 'On the 9 days you reached your water goal, your average mood was 3.8 (pleasant). On the 8 other days it was 3.1 (neutral). This compares your own days. It does not show a cause.',
              howWorkedOut: how,
            },
          ]
        : [],
    close: hasHistory ? ['Bedtime and mood: 3 more days with both a night and a mood check-in.'] : [],
    notice: crossDomain ? undefined : 'Comparisons between areas are off.',
  };
}
