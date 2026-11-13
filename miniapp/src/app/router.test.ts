import { afterEach, describe, expect, it } from 'vitest';
import { Router } from './router.svelte';

// The launch route of MINI_APP 3.4: `start_param` first, then the `route` query parameter that an inline
// web_app button of the bot sets, then the URL path.
describe('Router.start', () => {
  afterEach(() => window.history.replaceState(null, '', '/'));

  function startAt(url: string, startParam?: string): Router {
    window.history.replaceState(null, '', url);
    const router = new Router('/app/');
    router.start(startParam);
    return router;
  }

  it('opens the screen named by the route query parameter and drops the parameter from the address', () => {
    const router = startAt('/app/?route=water');
    expect(router.route).toEqual({ screen: 'water' });
    expect(router.unmatched).toBe(false);
    expect(window.location.pathname).toBe('/app/log/water');
    expect(window.location.search).toBe('');
  });

  it.each([
    ['today', { screen: 'today' }],
    ['mood', { screen: 'mood' }],
    ['sleep', { screen: 'sleep' }],
    ['reminders', { screen: 'reminders' }],
    ['insights', { screen: 'insights' }],
  ])('routes ?route=%s', (param, route) => {
    expect(startAt(`/app/?route=${param}`).route).toEqual(route);
  });

  it('prefers the startapp parameter to the route query parameter', () => {
    expect(startAt('/app/?route=water', 'mood').route).toEqual({ screen: 'mood' });
  });

  it('opens Today with the notice for a route query parameter that names no screen', () => {
    const router = startAt('/app/?route=food');
    expect(router.route).toEqual({ screen: 'today' });
    expect(router.unmatched).toBe(true);
    expect(window.location.search).toBe('');
  });

  it('falls back to the path when there is no launch parameter', () => {
    expect(startAt('/app/log/sleep').route).toEqual({ screen: 'sleep' });
  });
});
