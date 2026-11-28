// Stands in for insights/compare (MINI_APP 7.11, the Compare view): the mock's water of two periods
// as /stats compare prints them, last week against this week, this month against the last, or two
// months. A week shows a figure from 3 recorded days, a month from 14; "about the same" under 100 ml.
import type { CompareChoice, InsightCompare, InsightCompareCell, InsightComparePeriod, LocalDate } from '../api/types';
import { addDays, formatDate, isoWeekday } from './dates';
import type { MockTracking } from './tracking';

const WEEK_GATE = 3;
const MONTH_GATE = 14;
const SAME_ML = 100;
const PICKER_MONTHS = 12;

interface Window {
  readonly label: string;
  readonly start: LocalDate;
  readonly end: LocalDate;
}

const shortDate = (date: LocalDate): string => formatDate(date, { day: 'numeric', month: 'short' });
const monthLabel = (month: string): string => formatDate(`${month}-01`, { month: 'short', year: 'numeric' });

function datesOf(window: Window): LocalDate[] {
  const dates: LocalDate[] = [];
  for (let date = window.start; date <= window.end; date = addDays(date, 1)) dates.push(date);
  return dates;
}

function monthEnd(month: string): LocalDate {
  const [year, number] = month.split('-').map(Number) as [number, number];
  return new Date(Date.UTC(year, number, 0)).toISOString().slice(0, 10);
}

function monthWindow(month: string, today: LocalDate, label: string): Window {
  const end = monthEnd(month);
  return { label, start: `${month}-01`, end: end > today ? today : end };
}

function previousMonth(month: string): string {
  const [year, number] = month.split('-').map(Number) as [number, number];
  return number === 1 ? `${year - 1}-12` : `${year}-${String(number - 1).padStart(2, '0')}`;
}

function windowsOf(choice: CompareChoice, today: LocalDate): [Window, Window] {
  switch (choice.kind) {
    case 'weeks': {
      const monday = addDays(today, 1 - isoWeekday(today));
      return [
        { label: 'Last week', start: addDays(monday, -7), end: addDays(monday, -1) },
        { label: 'This week', start: monday, end: today },
      ];
    }
    case 'months': {
      const current = today.slice(0, 7);
      return [monthWindow(previousMonth(current), today, 'Last month'), monthWindow(current, today, 'This month')];
    }
    case 'pair': {
      const [earlier, later] = [choice.first, choice.second].sort() as [string, string];
      return [monthWindow(earlier, today, monthLabel(earlier)), monthWindow(later, today, monthLabel(later))];
    }
  }
}

const litres = (ml: number): string => (Math.round(ml / 100) / 10).toFixed(1);

function cellOf(window: Window, tracking: MockTracking, gate: number): { cell: InsightCompareCell; mean?: number } {
  const recorded = datesOf(window).map((date) => tracking.waterDay(date).totalMl).filter((ml) => ml > 0);
  const days = `${recorded.length} ${recorded.length === 1 ? 'day' : 'days'}`;
  if (recorded.length < gate) {
    return { cell: { text: recorded.length > 0 ? `records on ${days}` : '—', recordedDays: recorded.length } };
  }
  const mean = recorded.reduce((sum, ml) => sum + ml, 0) / recorded.length;
  const figure = `${litres(mean)} l`;
  return { cell: { text: `${figure} (${days})`, recordedDays: recorded.length, figure, value: Math.round(mean) }, mean };
}

function periodOf(window: Window, recordedDays: number): InsightComparePeriod {
  const days = datesOf(window).length;
  const range = window.start === window.end ? shortDate(window.start) : `${shortDate(window.start)} to ${shortDate(window.end)}`;
  return {
    label: window.label,
    start: window.start,
    end: window.end,
    days,
    daysWithRecords: recordedDays,
    summary: `${window.label}, ${range}: days with records ${recordedDays} of ${days}`,
  };
}

export function buildCompare(choice: CompareChoice | undefined, today: LocalDate, tracking: MockTracking): InsightCompare {
  const asked = choice ?? { kind: 'weeks' };
  const gate = asked.kind === 'weeks' ? WEEK_GATE : MONTH_GATE;
  const [first, second] = windowsOf(asked, today);
  const earlier = cellOf(first, tracking, gate);
  const later = cellOf(second, tracking, gate);
  const change =
    earlier.mean === undefined || later.mean === undefined
      ? undefined
      : Math.round(later.mean / 100) * 100 - Math.round(earlier.mean / 100) * 100;
  const difference =
    change === undefined
      ? undefined
      : Math.abs(change) < SAME_ML
        ? 'about the same'
        : `${litres(Math.abs(change))} l ${change > 0 ? 'higher' : 'lower'}`;
  const areas = tracking.isOn('hydration')
    ? [{ id: 'hydration' as const, title: 'Water, a day with records', earlier: earlier.cell, later: later.cell, difference }]
    : [];
  const periods = [periodOf(first, earlier.cell.recordedDays), periodOf(second, later.cell.recordedDays)] as const;
  const gateNote = `A ${asked.kind === 'weeks' ? 'week' : 'month'} shows its figure once it has records on ${gate} days; fewer show those days.`;
  const differenceNote = 'Each difference is the second period against the first.';
  const rows = areas.map(
    (area) =>
      `${area.title}: ${first.label} ${area.earlier.text} · ${second.label} ${area.later.text}${area.difference ? ` · ${area.difference}` : ''}`,
  );
  const recorded = earlier.cell.recordedDays + later.cell.recordedDays > 0;
  const current = today.slice(0, 7);
  const months: string[] = [];
  for (let month = current; months.length < PICKER_MONTHS; month = previousMonth(month)) months.push(month);
  return {
    selection: asked.kind,
    earlier: periods[0],
    later: periods[1],
    gateNote: areas.length > 0 ? gateNote : undefined,
    differenceNote,
    areas,
    months: months.map((month) => ({ month, label: monthLabel(month) })),
    lines: [
      'Compare two periods · Europe/Kyiv',
      periods[0].summary,
      periods[1].summary,
      ...(recorded && areas.length > 0
        ? [gateNote, differenceNote, ...rows, 'This compares your own days. It does not show a cause.']
        : ['Nothing was recorded in either period.']),
    ],
    notice:
      areas.length === 0
        ? 'Statistics appear here for the records you turned on in the chat.'
        : recorded
          ? undefined
          : 'Nothing was recorded in either period.',
  };
}
