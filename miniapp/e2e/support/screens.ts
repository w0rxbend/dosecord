// Every screen reachable by a route, for the screenshots (screens.spec.ts) and the accessibility scan (a11y.spec.ts).
const today = new Date().toISOString().slice(0, 10).replaceAll('-', '');

export const SCREENS: readonly (readonly [string, string])[] = [
  ['01-today', '/'],
  ['02-meds', '/meds'],
  ['03-med-new', '/meds/new'],
  ['07-log', '/log'],
  ['08-water', '/log/water'],
  ['09-water-settings', '/log/water/settings'],
  ['10-mood', '/log/mood'],
  ['11-mood-new', '/log/mood/new'],
  ['12-sleep', '/log/sleep'],
  ['13-insights', '/insights'],
  ['14-history', '/insights/history'],
  ['15-day', `/insights/history/${today}`],
  ['16-settings', '/settings'],
  ['17-time-zone', '/settings/tz'],
  ['18-quiet-hours', '/settings/notif'],
  ['19-privacy', '/settings/privacy'],
  ['20-reminders', '/settings/reminders'],
  ['22-setup', '/setup'],
  ['23-day-profile', '/settings/day'],
  ['24-food', '/log/food'],
  ['25-reports', '/insights/reports'],
];
