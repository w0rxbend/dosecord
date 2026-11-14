// Instants of the reminders API shown as clock times in the account's zone, the zone the server
// computed them in (ADR-023), in the account's language.
import { dateLocale } from '../../i18n/index.svelte';

export function clockIn(timeZone: string, instant: string): string {
  return new Intl.DateTimeFormat(dateLocale(), { timeZone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(
    new Date(instant),
  );
}

export function dayAndClockIn(timeZone: string, instant: string): string {
  return new Intl.DateTimeFormat(dateLocale(), {
    timeZone,
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  })
    .format(new Date(instant))
    .replace(',', '');
}
