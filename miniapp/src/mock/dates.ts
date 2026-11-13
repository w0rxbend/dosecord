// Calendar arithmetic on local dates, done in UTC so that no device time zone leaks in.
import type { LocalDate, LocalTime } from '../api/types';

const DAY_MS = 86_400_000;
const pad = (value: number): string => String(value).padStart(2, '0');

export function toLocalDate(date: Date): LocalDate {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function toUtc(date: LocalDate): Date {
  return new Date(`${date}T00:00:00Z`);
}

export function addDays(date: LocalDate, days: number): LocalDate {
  return new Date(toUtc(date).getTime() + days * DAY_MS).toISOString().slice(0, 10);
}

/** ISO weekday: 1 is Monday, 7 is Sunday. */
export function isoWeekday(date: LocalDate): number {
  return toUtc(date).getUTCDay() || 7;
}

export function formatDate(date: LocalDate, options: Intl.DateTimeFormatOptions): string {
  // ICU versions disagree about a comma after the weekday; the mock writes none.
  return new Intl.DateTimeFormat('en-GB', { ...options, timeZone: 'UTC' }).format(toUtc(date)).replace(',', '');
}

export const toMinutes = (time: LocalTime): number => Number(time.slice(0, 2)) * 60 + Number(time.slice(3));
export const toTime = (minutes: number): LocalTime => `${pad(Math.floor(minutes / 60) % 24)}:${pad(minutes % 60)}`;
