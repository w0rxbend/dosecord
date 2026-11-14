// Route grammar and internal paths (MINI_APP 3.4). A route selects a screen and nothing else;
// it never grants access (MA-ROUTE-1).

export type Route =
  | { readonly screen: 'today'; readonly date?: string }
  | { readonly screen: 'meds' }
  | { readonly screen: 'med-new' }
  | { readonly screen: 'med-edit'; readonly id: string }
  | { readonly screen: 'schedule-new'; readonly medicationId: string }
  | { readonly screen: 'schedule-edit'; readonly id: string }
  | { readonly screen: 'log' }
  | { readonly screen: 'water' }
  | { readonly screen: 'water-settings' }
  | { readonly screen: 'mood' }
  | { readonly screen: 'mood-new' }
  | { readonly screen: 'sleep' }
  | { readonly screen: 'food' }
  | { readonly screen: 'insights' }
  | { readonly screen: 'history' }
  | { readonly screen: 'day'; readonly date: string }
  | { readonly screen: 'settings' }
  | { readonly screen: 'settings-tz' }
  | { readonly screen: 'settings-notif' }
  | { readonly screen: 'settings-privacy' }
  | { readonly screen: 'reminders' };

export type TabId = 'today' | 'meds' | 'log' | 'insights';

const ID = '[A-Za-z0-9_-]{1,64}';
const DATE = '(\\d{4})(\\d{2})(\\d{2})';

const isoDate = (m: RegExpExecArray): string => `${m[1]}-${m[2]}-${m[3]}`;

const PATHS: readonly (readonly [RegExp, (match: RegExpExecArray) => Route])[] = [
  [/^\/$/, () => ({ screen: 'today' })],
  [new RegExp(`^/day/${DATE}$`), (m) => ({ screen: 'today', date: isoDate(m) })],
  [/^\/meds$/, () => ({ screen: 'meds' })],
  [/^\/meds\/new$/, () => ({ screen: 'med-new' })],
  [new RegExp(`^/meds/(${ID})/edit$`), (m) => ({ screen: 'med-edit', id: m[1]! })],
  [new RegExp(`^/meds/(${ID})/schedules/new$`), (m) => ({ screen: 'schedule-new', medicationId: m[1]! })],
  [new RegExp(`^/schedules/(${ID})$`), (m) => ({ screen: 'schedule-edit', id: m[1]! })],
  [/^\/log$/, () => ({ screen: 'log' })],
  [/^\/log\/water$/, () => ({ screen: 'water' })],
  [/^\/log\/water\/settings$/, () => ({ screen: 'water-settings' })],
  [/^\/log\/mood$/, () => ({ screen: 'mood' })],
  [/^\/log\/mood\/new$/, () => ({ screen: 'mood-new' })],
  [/^\/log\/sleep$/, () => ({ screen: 'sleep' })],
  [/^\/log\/food$/, () => ({ screen: 'food' })],
  [/^\/insights$/, () => ({ screen: 'insights' })],
  [/^\/insights\/history$/, () => ({ screen: 'history' })],
  [new RegExp(`^/insights/history/${DATE}$`), (m) => ({ screen: 'day', date: isoDate(m) })],
  [/^\/settings$/, () => ({ screen: 'settings' })],
  [/^\/settings\/tz$/, () => ({ screen: 'settings-tz' })],
  [/^\/settings\/notif$/, () => ({ screen: 'settings-notif' })],
  [/^\/settings\/privacy$/, () => ({ screen: 'settings-privacy' })],
  [/^\/settings\/reminders$/, () => ({ screen: 'reminders' })],
];

export function parsePath(path: string): Route | null {
  for (const [pattern, build] of PATHS) {
    const match = pattern.exec(path);
    if (match) return build(match);
  }
  return null;
}

const START_PARAMS: readonly (readonly [RegExp, (match: RegExpExecArray) => string])[] = [
  [/^today$/, () => '/'],
  [new RegExp(`^today_(${DATE})$`), (m) => `/day/${m[1]}`],
  [/^meds$/, () => '/meds'],
  [/^mednew$/, () => '/meds/new'],
  [new RegExp(`^medit_(${ID})$`), (m) => `/meds/${m[1]}/edit`],
  [new RegExp(`^schednew_(${ID})$`), (m) => `/meds/${m[1]}/schedules/new`],
  [new RegExp(`^sched_(${ID})$`), (m) => `/schedules/${m[1]}`],
  [/^log$/, () => '/log'],
  [/^water$/, () => '/log/water'],
  [/^mood$/, () => '/log/mood'],
  [/^moodnew$/, () => '/log/mood/new'],
  [/^sleep$/, () => '/log/sleep'],
  [/^food$/, () => '/log/food'],
  [/^insights$/, () => '/insights'],
  [/^hist$/, () => '/insights/history'],
  [new RegExp(`^hist_(${DATE})$`), (m) => `/insights/history/${m[1]}`],
  [/^settings$/, () => '/settings'],
  [/^set_tz$/, () => '/settings/tz'],
  [/^set_notif$/, () => '/settings/notif'],
  [/^set_privacy$/, () => '/settings/privacy'],
  [/^reminders$/, () => '/settings/reminders'],
];

/** Maps a startapp parameter to an internal path; null when it matches no enabled screen. */
export function pathFromStartParam(param: string): string | null {
  for (const [pattern, build] of START_PARAMS) {
    const match = pattern.exec(param);
    if (match) return build(match);
  }
  return null;
}

const TABS: Readonly<Record<Route['screen'], TabId | null>> = {
  today: 'today',
  meds: 'meds',
  'med-new': 'meds',
  'med-edit': 'meds',
  'schedule-new': 'meds',
  'schedule-edit': 'meds',
  log: 'log',
  water: 'log',
  'water-settings': 'log',
  mood: 'log',
  'mood-new': 'log',
  sleep: 'log',
  food: 'log',
  insights: 'insights',
  history: 'insights',
  day: 'insights',
  settings: null,
  'settings-tz': null,
  'settings-notif': null,
  'settings-privacy': null,
  reminders: null,
};

/** The tab a screen belongs to; settings sit outside the tabs (MINI_APP 4.1). */
export function tabOf(route: Route): TabId {
  return TABS[route.screen] ?? 'today';
}

/** The tab bar is shown on tab roots and nowhere else (MA-NAV-1). */
export function isTabRoot(route: Route): boolean {
  return route.screen === 'today' || route.screen === 'meds' || route.screen === 'log' || route.screen === 'insights';
}

const PARENTS: Readonly<Partial<Record<Route['screen'], string>>> = {
  'med-new': '/meds',
  'med-edit': '/meds',
  'schedule-new': '/meds',
  'schedule-edit': '/meds',
  water: '/log',
  'water-settings': '/log/water',
  mood: '/log',
  'mood-new': '/log/mood',
  sleep: '/log',
  food: '/log',
  history: '/insights',
  day: '/insights/history',
  settings: '/',
  'settings-tz': '/settings',
  'settings-notif': '/settings',
  'settings-privacy': '/settings',
  reminders: '/settings',
};

/** Where BackButton leads when the screen was opened by a route (MA-ROUTE-4). */
export function parentPath(route: Route): string {
  return PARENTS[route.screen] ?? '/';
}

export function dayPath(date: string): string {
  return `/day/${date.replaceAll('-', '')}`;
}

/** Day detail opened from the history calendar (S-13). */
export function historyDayPath(date: string): string {
  return `/insights/history/${date.replaceAll('-', '')}`;
}
