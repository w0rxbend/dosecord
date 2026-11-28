// Stands in for insights/year (MINI_APP 7.11, the Year tab): the mock's water and mood records of
// the year, a table of months per area as /stats year prints it, and the weeks of water and mood as
// the server lays them out. Medication has no week chart (PRODUCT_SPEC R-GAM-2).
import type { Chart, InsightYear, InsightYearArea, LocalDate, SceneNode, TrackedDomain } from '../api/types';
import { addDays, formatDate, isoWeekday } from './dates';
import { doseHistory } from './insights';
import type { MockTracking } from './tracking';

const WIDTH = 320;
const LABEL_ROOM = 18;
const TRACK = 36;
const GATE_DAYS = 14;

const shortDate = (date: LocalDate): string => formatDate(date, { day: 'numeric', month: 'short' });

function datesOf(start: LocalDate, end: LocalDate): LocalDate[] {
  const dates: LocalDate[] = [];
  for (let date = start; date <= end; date = addDays(date, 1)) dates.push(date);
  return dates;
}

/** Monday weeks from the week of 1 January, cut at the year's edges. */
function weeksOf(dates: readonly LocalDate[]): LocalDate[][] {
  const weeks: LocalDate[][] = [];
  for (const date of dates) {
    if (weeks.length === 0 || isoWeekday(date) === 1) weeks.push([]);
    weeks.at(-1)!.push(date);
  }
  return weeks;
}

function daysText(count: number, of: number): string {
  return count === 0 ? 'no day with a record' : `${count} of ${of} days with a record`;
}

function weeksChart(id: TrackedDomain, title: string, weeks: readonly LocalDate[][], recorded: ReadonlySet<LocalDate>): Chart {
  const pitch = WIDTH / weeks.length;
  const cell = Math.max(2, pitch * 0.7);
  const counts = weeks.map((week) => week.filter((date) => recorded.has(date)).length);
  const nodes = weeks.flatMap((week, index): SceneNode[] => {
    const x = index * pitch + (pitch - cell) / 2;
    const count = counts[index]!;
    const height = Math.max(8, (TRACK * count) / week.length);
    const drawn: SceneNode[] =
      count === 0
        ? [{ kind: 'rect', x, y: LABEL_ROOM + TRACK - 4, width: cell, height: 4, fill: 'text-muted', outlined: true }]
        : [
            { kind: 'rect', x, y: LABEL_ROOM, width: cell, height: TRACK, fill: 'separator' },
            { kind: 'rect', x, y: LABEL_ROOM + TRACK - height, width: cell, height, fill: `domain-${id}` },
          ];
    return [...drawn, { kind: 'hit', x: index * pitch, y: LABEL_ROOM, width: pitch, height: TRACK, mark: index }];
  });
  const label = (week: readonly LocalDate[]): string => `${shortDate(week[0]!)} to ${shortDate(week.at(-1)!)}`;
  const heading = 'Days with a record, week by week';
  return {
    id: `year-weeks-${id}`,
    title: heading,
    summary: `${title}: days with a record week by week, ${recorded.size} of ${weeks.flat().length} days.`,
    scene: { width: WIDTH, height: LABEL_ROOM + TRACK + 2, nodes },
    table: {
      caption: heading,
      columns: ['Week', 'Days with a record'],
      rows: weeks.map((week, index) => [label(week), daysText(counts[index]!, week.length)]),
    },
    marks: weeks.map((week, index) => `${label(week)}: ${daysText(counts[index]!, week.length)}`),
  };
}

interface AreaSource {
  readonly id: TrackedDomain;
  readonly title: string;
  readonly figure: string;
  readonly recorded: ReadonlySet<LocalDate>;
}

function areaOf(source: AreaSource, year: number, dates: readonly LocalDate[]): InsightYearArea {
  const months = [...new Set(dates.map((date) => date.slice(0, 7)))].map((month) => {
    const recordedDays = dates.filter((date) => date.startsWith(month) && source.recorded.has(date)).length;
    const text = recordedDays >= GATE_DAYS ? source.figure : recordedDays > 0 ? `(${recordedDays})` : '—';
    return { month, label: formatDate(`${month}-01`, { month: 'short' }), recordedDays, text };
  });
  const recordedDays = source.recorded.size;
  return {
    id: source.id,
    title: source.title,
    recordedDays,
    months,
    total: recordedDays >= GATE_DAYS ? `${year}: ${source.figure} (${recordedDays} days)` : undefined,
    heat: source.id === 'medication' ? undefined : weeksChart(source.id, source.title, weeksOf(dates), source.recorded),
  };
}

export function buildYear(asked: number | undefined, today: LocalDate, tracking: MockTracking): InsightYear {
  const current = Number(today.slice(0, 4));
  const year = asked ?? current;
  const end = year === current ? today : `${year}-12-31`;
  const dates = datesOf(`${year}-01-01`, end);
  const own = (has: (date: LocalDate) => boolean): ReadonlySet<LocalDate> => new Set(dates.filter(has));
  const sources: AreaSource[] = [];
  if (tracking.isOn('medication')) {
    // The last 30 days of the synthetic dose history, as the overview reads it.
    const history = new Set(doseHistory(dates.slice(-30)).map((day) => day.date));
    sources.push({ id: 'medication', title: 'Doses taken of those answered', figure: '91%', recorded: history });
  }
  if (tracking.isOn('hydration')) {
    const recorded = own((date) => tracking.waterDay(date).totalMl > 0);
    sources.push({ id: 'hydration', title: 'Water, a day with records', figure: '1.7 l', recorded });
  }
  if (tracking.isOn('mood')) {
    const recorded = own((date) => tracking.moodDay(date).checkIns > 0);
    sources.push({ id: 'mood', title: 'Mood, average of the days with a check-in', figure: '3.6', recorded });
  }
  const areas = sources.map((source) => areaOf(source, year, dates));
  const withRecords = new Set(sources.flatMap((source) => [...source.recorded]));
  const summary = `Days with records: ${withRecords.size} of ${dates.length}`;
  const lines = [
    `${year} · Europe/Kyiv`,
    summary,
    ...areas.map((area) => `${area.title}: ${area.months.map((month) => `${month.label} ${month.text}`).join(' · ')}`),
  ];
  return {
    year,
    yearLabel: `${year} · Europe/Kyiv`,
    previousYear: undefined,
    nextYear: year < current ? year + 1 : undefined,
    days: dates.length,
    daysWithRecords: withRecords.size,
    summary,
    gateNote: 'A month shows its figure once it has records on 14 days; fewer show those days in brackets.',
    areas,
    lines,
    notice:
      areas.length === 0
        ? 'Statistics appear here for the records you turned on in the chat.'
        : withRecords.size === 0
          ? `Nothing was recorded in ${year}.`
          : undefined,
  };
}
