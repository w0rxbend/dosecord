import { APP_CONTEXT, type AppContext } from '../app/context';
import { Router } from '../app/router.svelte';
import { MockBackend, type MockScenario } from '../mock/backend';
import { FakeTelegramPort } from '../platform/fake';
import { Toasts } from '../state/toasts.svelte';

/** Thursday 12 November 2026, 08:12 local time: Medication A has been due for 12 minutes. */
export const FIXED_NOW = new Date(2026, 10, 12, 8, 12);

export interface Harness extends AppContext {
  readonly api: MockBackend;
  readonly tg: FakeTelegramPort;
  /** Pass as the `context` render option. */
  readonly context: Map<symbol, AppContext>;
}

/** A screen's whole world: the fake port, the mock backend at a fixed time, a router. */
export function createHarness(scenario: MockScenario = 'typical'): Harness {
  window.history.replaceState(null, '', '/');
  const tg = new FakeTelegramPort();
  const api = new MockBackend({ now: () => FIXED_NOW, scenario });
  const router = new Router('/');
  router.start(undefined);
  const app = { api, tg, router, toasts: new Toasts() };
  return { ...app, context: new Map([[APP_CONTEXT, app]]) };
}
