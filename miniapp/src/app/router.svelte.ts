// History-API router with URL paths; the fragment belongs to the Telegram script (MA-ROUTE-3).
import { parentPath, parsePath, pathFromStartParam, type Route } from './routes';

const HOME: Route = { screen: 'today' };

/** Asked before leaving a screen; a dirty form answers false to stay (MA-NAV-5). */
export type LeaveGuard = () => Promise<boolean>;

export class Router {
  route = $state.raw<Route>(HOME);
  /** True when the launch route matched no enabled screen (MA-ROUTE-2). */
  unmatched = $state(false);

  private depth = 0;
  private guard: LeaveGuard | null = null;

  constructor(
    private readonly base: string,
    private readonly host: Window = window,
  ) {}

  /**
   * Picks the first screen (MINI_APP 3.4): the startapp parameter, then the `route` query parameter of an
   * inline web_app button, then the URL path. The address keeps the path only.
   */
  start(startParam: string | undefined): () => void {
    const launchParam = startParam ?? this.routeQuery();
    const fromParam = launchParam === undefined ? null : pathFromStartParam(launchParam);
    const path = fromParam ?? this.currentPath();
    const route = parsePath(path);
    this.unmatched = route === null || (launchParam !== undefined && fromParam === null);
    this.route = route ?? HOME;
    this.host.history.replaceState(null, '', this.hrefFor(route ? path : '/'));

    const onPop = (): void => {
      this.depth = Math.max(0, this.depth - 1);
      this.route = parsePath(this.currentPath()) ?? HOME;
    };
    this.host.addEventListener('popstate', onPop);
    return () => this.host.removeEventListener('popstate', onPop);
  }

  setGuard(guard: LeaveGuard | null): void {
    this.guard = guard;
  }

  async navigate(path: string): Promise<void> {
    const route = parsePath(path);
    if (!route || !(await this.mayLeave())) return;
    this.host.history.pushState(null, '', this.hrefFor(path));
    this.depth += 1;
    this.route = route;
  }

  /** Replaces the current entry: for a finished task screen and for tab changes. */
  async replace(path: string): Promise<void> {
    const route = parsePath(path);
    if (!route || !(await this.mayLeave())) return;
    this.host.history.replaceState(null, '', this.hrefFor(path));
    this.route = route;
  }

  /** The previous entry, or the logical parent when the screen was opened by a route. */
  async back(): Promise<void> {
    if (!(await this.mayLeave())) return;
    if (this.depth > 0) this.host.history.back();
    else {
      const parent = parentPath(this.route);
      this.host.history.replaceState(null, '', this.hrefFor(parent));
      this.route = parsePath(parent) ?? HOME;
    }
  }

  private async mayLeave(): Promise<boolean> {
    if (this.guard && !(await this.guard())) return false;
    this.guard = null;
    return true;
  }

  private routeQuery(): string | undefined {
    return new URLSearchParams(this.host.location.search).get('route') ?? undefined;
  }

  private currentPath(): string {
    const { pathname } = this.host.location;
    const root = this.base.replace(/\/$/, '');
    const path = pathname.startsWith(root) ? pathname.slice(root.length) : pathname;
    return `/${path.replace(/^\/+|\/+$/g, '')}`;
  }

  /** The address of an internal path, for real links. */
  hrefFor(path: string): string {
    return `${this.base.replace(/\/$/, '')}${path}`;
  }
}
