// Instants of the reminders API shown as clock times in the account's zone, the zone the server
// computed them in (ADR-023).

export function clockIn(timeZone: string, instant: string): string {
  return new Intl.DateTimeFormat('en-GB', { timeZone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(
    new Date(instant),
  );
}

export function dayAndClockIn(timeZone: string, instant: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    timeZone,
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  })
    .format(new Date(instant))
    .replace(',', '');
}
