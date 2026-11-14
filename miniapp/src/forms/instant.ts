import type { LocalDate } from '../api/types';

const pad = (value: number): string => String(Math.floor(Math.abs(value))).padStart(2, '0');

/** The offset this device has at a wall-clock time, as `+02:00`. */
function offsetAt(date: LocalDate, time: string): string {
  const minutes = -new Date(`${date}T${time}:00`).getTimezoneOffset();
  return `${minutes < 0 ? '-' : '+'}${pad(minutes / 60)}:${pad(minutes % 60)}`;
}

/** A wall-clock date and time as an instant with the device offset, ISO 8601: the server stores instants. */
export function localInstant(date: LocalDate, time: string): string {
  return `${date}T${time}:00${offsetAt(date, time)}`;
}
