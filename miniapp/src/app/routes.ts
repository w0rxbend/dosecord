// Route grammar and internal paths (MINI_APP 3.4). A route selects a screen and nothing else;
// it never grants access (MA-ROUTE-1).

export type Route =
  | { readonly screen: 'today'; readonly date?: string }
  | { readonly screen: 'meds' }
  | { readonly screen: 'med-new' }
  | { readonly screen: 'med-edit'; readonly id: string }
  | { readonly screen: 'schedule-new'; readonly medicationId: string }
  | { readonly screen: 'insights' };

export type TabId = 'today' | 'meds' | 'insights';

const ID = '[A-Za-z0-9_-]{1,64}';
const DATE = '(\\d{4})(\\d{2})(\\d{2})';

const PATHS: readonly (readonly [RegExp, (match: RegExpExecArray) => Route])[] = [
  [/^\/$/, () => ({ screen: 'today' })],
  [new RegExp(`^/day/${DATE}$`), (m) => ({ screen: 'today', date: `${m[1]}-${m[2]}-${m[3]}` })],
  [/^\/meds$/, () => ({ screen: 'meds' })],
  [/^\/meds\/new$/, () => ({ screen: 'med-new' })],
  [new RegExp(`^/meds/(${ID})/edit$`), (m) => ({ screen: 'med-edit', id: m[1]! })],
  [new RegExp(`^/meds/(${ID})/schedules/new$`), (m) => ({ screen: 'schedule-new', medicationId: m[1]! })],
  [/^\/insights$/, () => ({ screen: 'insights' })],
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
  [/^insights$/, () => '/insights'],
];

/** Maps a startapp parameter to an internal path; null when it matches no enabled screen. */
export function pathFromStartParam(param: string): string | null {
  for (const [pattern, build] of START_PARAMS) {
    const match = pattern.exec(param);
    if (match) return build(match);
  }
  return null;
}

export function tabOf(route: Route): TabId {
  if (route.screen === 'today' || route.screen === 'insights') return route.screen;
  return 'meds';
}

/** The tab bar is shown on tab roots and nowhere else (MA-NAV-1). */
export function isTabRoot(route: Route): boolean {
  return route.screen === 'today' || route.screen === 'meds' || route.screen === 'insights';
}

/** Where BackButton leads when the screen was opened by a route (MA-ROUTE-4). */
export function parentPath(route: Route): string {
  return isTabRoot(route) ? '/' : '/meds';
}

export function dayPath(date: string): string {
  return `/day/${date.replaceAll('-', '')}`;
}
