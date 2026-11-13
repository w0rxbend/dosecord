// Stands in for the server's chart layout (MINI_APP 5.6): each chart is a scene of positioned
// primitives plus its text summary and data table. The client only paints what it is given.
import type { Chart, LocalDate, SceneNode } from '../api/types';
import { addDays, formatDate, isoWeekday } from './dates';

const WIDTH = 320;
const LABEL_SIZE = 13;

export interface DoseDay {
  readonly date: LocalDate;
  readonly scheduled: number;
  readonly taken: number;
}

export interface DoseOutcomes {
  readonly onTime: number;
  readonly late: number;
  readonly skipped: number;
  readonly notConfirmed: number;
  readonly noRecord: number;
}

export interface WaterDay {
  readonly date: LocalDate;
  readonly totalMl: number;
}

const shortDate = (date: LocalDate): string => formatDate(date, { day: 'numeric', month: 'short' });
const litres = (ml: number): string => `${(ml / 1000).toFixed(1)} L`;

export function outcomeLabels(outcomes: DoseOutcomes): readonly (readonly [string, number])[] {
  return [
    ['on time', outcomes.onTime],
    ['late', outcomes.late],
    ['skipped', outcomes.skipped],
    ['not confirmed', outcomes.notConfirmed],
    ['no record', outcomes.noRecord],
  ];
}

/** One horizontal stacked bar; the counts are printed in the summary, so no legend lookup. */
export function outcomeBar(outcomes: DoseOutcomes): Chart {
  const labels = outcomeLabels(outcomes);
  const total = labels.reduce((sum, [, count]) => sum + count, 0);
  // Recorded doses are filled; unanswered ones are outlined. No red, no green-versus-red.
  const styles: Pick<Extract<SceneNode, { kind: 'rect' }>, 'fill' | 'opacity' | 'outlined'>[] = [
    { fill: 'recorded' },
    { fill: 'recorded', opacity: 0.55 },
    { fill: 'text-muted' },
    { fill: 'text', opacity: 0.25, outlined: true },
    { fill: 'separator', outlined: true },
  ];
  let x = 0;
  const nodes = labels.flatMap(([, count], index): SceneNode[] => {
    const width = (count / total) * WIDTH;
    const rect: SceneNode = { kind: 'rect', x, y: 0, width, height: 20, ...styles[index]! };
    x += width;
    return count > 0 ? [rect] : [];
  });
  const summary = labels.map(([label, count]) => `${count} ${label}`).join(', ');
  return {
    id: 'dose-outcomes',
    title: 'Dose outcomes',
    summary: `Of ${total} scheduled doses: ${summary}.`,
    scene: { width: WIDTH, height: 20, nodes },
    table: {
      caption: 'Dose outcomes in this period',
      columns: ['Outcome', 'Doses'],
      rows: labels.map(([label, count]) => [label, String(count)]),
    },
    marks: labels.filter(([, count]) => count > 0).map(([label, count]) => `${count} ${label}`),
  };
}

/** Weeks as columns, weekdays as rows. Shade is the share of scheduled doses recorded as taken. */
export function doseCalendar(days: readonly DoseDay[]): Chart {
  const gutter = 36;
  const firstWeekday = isoWeekday(days[0]!.date);
  const columns = Math.ceil((days.length + firstWeekday - 1) / 7);
  const pitch = Math.min(32, Math.floor((WIDTH - gutter) / columns));
  const cell = pitch - 2;
  const cells = days.map((day, index): SceneNode => {
    const slot = index + firstWeekday - 1;
    const share = day.scheduled === 0 ? null : day.taken / day.scheduled;
    return {
      kind: 'rect',
      x: gutter + Math.floor(slot / 7) * pitch,
      y: (slot % 7) * pitch,
      width: cell,
      height: cell,
      radius: 3,
      fill: share === null ? 'separator' : 'domain-medication',
      opacity: share === null ? 1 : 0.2 + 0.8 * share,
      outlined: share !== null && share < 1,
      mark: index,
    };
  });
  const labels = (['Mon', 'Wed', 'Fri', 'Sun'] as const).map(
    (text, index): SceneNode => ({
      kind: 'text',
      x: 0,
      y: index * 2 * pitch + cell / 2 + LABEL_SIZE / 3,
      text,
      fill: 'text-muted',
    }),
  );
  const complete = days.filter((day) => day.scheduled > 0 && day.taken === day.scheduled).length;
  const withDoses = days.filter((day) => day.scheduled > 0).length;
  return {
    id: 'doses-by-day',
    title: 'Doses recorded by day',
    summary: `All scheduled doses were recorded as taken on ${complete} of ${withDoses} days with doses.`,
    scene: { width: WIDTH, height: 7 * pitch, nodes: [...labels, ...cells] },
    table: {
      caption: 'Doses recorded as taken, by day',
      columns: ['Day', 'Taken', 'Scheduled'],
      rows: days.map((day) => [shortDate(day.date), String(day.taken), String(day.scheduled)]),
    },
    marks: days.map((day) => `${shortDate(day.date)}: ${day.taken} of ${day.scheduled} taken`),
  };
}

/** Bars from zero with a dashed goal line (MINI_APP 7.11); a day without water is a gap. */
export function waterBars(days: readonly WaterDay[], goalMl: number | undefined): Chart {
  const plotHeight = 96;
  const axisWidth = 44;
  const top = Math.max(goalMl ?? 0, ...days.map((day) => day.totalMl)) * 1.1;
  const pitch = (WIDTH - axisWidth) / days.length;
  const scale = (ml: number): number => plotHeight - (ml / top) * plotHeight;
  const bars = days.flatMap((day, index): SceneNode[] =>
    day.totalMl === 0
      ? []
      : [
          {
            kind: 'rect',
            x: axisWidth + index * pitch + pitch * 0.15,
            y: scale(day.totalMl),
            width: pitch * 0.7,
            height: plotHeight - scale(day.totalMl),
            radius: Math.min(2, pitch * 0.2),
            fill: 'domain-hydration',
            mark: index,
          },
        ],
  );
  const goal: SceneNode[] =
    goalMl === undefined
      ? []
      : [
          { kind: 'line', x1: axisWidth, y1: scale(goalMl), x2: WIDTH, y2: scale(goalMl), stroke: 'text-muted', dashed: true },
          { kind: 'text', x: 0, y: scale(goalMl) + LABEL_SIZE / 3, text: litres(goalMl), fill: 'text-muted' },
        ];
  const hits = days.map(
    (_, index): SceneNode => ({ kind: 'hit', x: axisWidth + index * pitch, y: 0, width: pitch, height: plotHeight, mark: index }),
  );
  const nodes: SceneNode[] = [
    ...bars,
    { kind: 'line', x1: axisWidth, y1: plotHeight, x2: WIDTH, y2: plotHeight, stroke: 'separator' },
    ...goal,
    { kind: 'text', x: axisWidth, y: plotHeight + 18, text: shortDate(days[0]!.date), fill: 'text-muted' },
    { kind: 'text', x: WIDTH, y: plotHeight + 18, text: shortDate(days.at(-1)!.date), fill: 'text-muted', anchor: 'end' },
    ...hits,
  ];
  const recorded = days.filter((day) => day.totalMl > 0);
  const reached = goalMl === undefined ? '' : ` The goal was reached on ${recorded.filter((day) => day.totalMl >= goalMl).length} of ${recorded.length} recorded days.`;
  const valueOf = (day: WaterDay): string => (day.totalMl === 0 ? 'no entry' : litres(day.totalMl));
  return {
    id: 'water-by-day',
    title: 'Water per day',
    summary: `Water per day, ${shortDate(days[0]!.date)} to ${shortDate(days.at(-1)!.date)}. Entries on ${recorded.length} of ${days.length} days.${reached}`,
    scene: { width: WIDTH, height: plotHeight + 24, nodes },
    table: {
      caption: 'Water per day',
      columns: ['Day', 'Water'],
      rows: days.map((day) => [shortDate(day.date), day.totalMl === 0 ? 'No entry' : litres(day.totalMl)]),
    },
    marks: days.map((day) => `${shortDate(day.date)}: ${valueOf(day)}`),
  };
}

export interface MoodPoint {
  readonly date: LocalDate;
  readonly mean: number | null;
  readonly checkIns: number;
}

/** The line of the daily mean with a dot per day that has one; a day without one is a gap. */
export function moodLine(days: readonly MoodPoint[]): Chart {
  const plotHeight = 96;
  const plotTop = 16;
  const gutter = 96;
  const pitch = (WIDTH - gutter) / days.length;
  const xOf = (index: number): number => gutter + index * pitch + pitch / 2;
  const yOf = (mean: number): number => plotTop + ((5 - mean) / 4) * (plotHeight - plotTop);
  const levels = ([[5, 'Very pleasant'], [3, 'Neutral'], [1, 'Very unpleasant']] as const).flatMap(
    ([step, name]): SceneNode[] => [
      { kind: 'line', x1: gutter, y1: yOf(step), x2: WIDTH, y2: yOf(step), stroke: 'separator' },
      { kind: 'text', x: 0, y: yOf(step) + 4, text: name, fill: 'text-muted' },
    ],
  );
  const joins = days.slice(1).flatMap((day, index): SceneNode[] => {
    const before = days[index]!;
    return day.mean === null || before.mean === null
      ? []
      : [{ kind: 'line', x1: xOf(index), y1: yOf(before.mean), x2: xOf(index + 1), y2: yOf(day.mean), stroke: 'domain-mood' }];
  });
  const dots = days.flatMap((day, index): SceneNode[] =>
    day.mean === null ? [] : [{ kind: 'circle', x: xOf(index), y: yOf(day.mean), r: 3.5, fill: 'domain-mood', mark: index }],
  );
  const hits = days.map(
    (_, index): SceneNode => ({ kind: 'hit', x: gutter + index * pitch, y: 0, width: pitch, height: plotHeight, mark: index }),
  );
  const recorded = days.filter((day) => day.mean !== null).length;
  const valueOf = (day: MoodPoint): string =>
    day.mean === null ? 'no check-in' : `${day.mean.toFixed(1)}, ${day.checkIns === 1 ? '1 check-in' : `${day.checkIns} check-ins`}`;
  return {
    id: 'mood-by-day',
    title: 'Mood per day',
    summary: `Daily mood, ${shortDate(days[0]!.date)} to ${shortDate(days.at(-1)!.date)}. Check-ins on ${recorded} of ${days.length} days. A day without a check-in is a gap.`,
    scene: {
      width: WIDTH,
      height: plotHeight + 24,
      nodes: [
        ...levels,
        ...joins,
        ...dots,
        { kind: 'text', x: gutter, y: plotHeight + 18, text: shortDate(days[0]!.date), fill: 'text-muted' },
        { kind: 'text', x: WIDTH, y: plotHeight + 18, text: shortDate(days.at(-1)!.date), fill: 'text-muted', anchor: 'end' },
        ...hits,
      ],
    },
    table: {
      caption: 'Mood per day',
      columns: ['Day', 'Daily mood', 'Check-ins'],
      rows: days.map((day) => [shortDate(day.date), day.mean === null ? 'No check-in' : day.mean.toFixed(1), String(day.checkIns)]),
    },
    marks: days.map((day) => `${shortDate(day.date)}: ${valueOf(day)}`),
  };
}

/** Deterministic pseudo-random numbers, so the synthetic history is the same on every run. */
export function seeded(seed: number): () => number {
  let state = seed;
  return () => {
    state = (state * 1_664_525 + 1_013_904_223) % 4_294_967_296;
    return state / 4_294_967_296;
  };
}

export function lastDays(today: LocalDate, count: number): LocalDate[] {
  return Array.from({ length: count }, (_, index) => addDays(today, index - count + 1));
}
