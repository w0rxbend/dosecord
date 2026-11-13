import { mount } from 'svelte';
import type { ApiClient } from '../api/client';
import { createHttpApi } from '../api/http';
import { bindAppearance } from '../platform/apply-theme';
import type { TelegramPort } from '../platform/port';
import { createRealTelegramPort } from '../platform/real';
import { Toasts } from '../state/toasts.svelte';
import App from './App.svelte';
import Gate from './Gate.svelte';
import { Router } from './router.svelte';

/** The session exchange needs the Ed25519 signature, which exists since Bot API 8.0 (3.3). */
const MINIMUM_CLIENT = '8.0';
const API_BASE = '/api/v1';

interface Environment {
  readonly tg: TelegramPort;
  readonly api: ApiClient;
}

type Gated = 'outside' | 'oldClient';

function productionEnvironment(): Environment | Gated {
  const tg = createRealTelegramPort();
  // Outside Telegram the official script still loads, with an empty initData (TGF-39).
  if (!tg || tg.initData === '') return 'outside';
  if (!tg.isVersionAtLeast(MINIMUM_CLIENT)) return 'oldClient';
  const api = createHttpApi({ baseUrl: API_BASE, initData: tg.initData, colorScheme: tg.theme().scheme });
  return { tg, api };
}

/**
 * The fake port and the mock backend are chosen by build configuration, not by sniffing the
 * environment (MINI_APP 14.1). The dynamic import sits behind a build-time constant, so a
 * production bundle contains neither (MA-TEST-2).
 */
async function environment(): Promise<Environment | Gated> {
  if (import.meta.env.DEV) {
    const { createDevEnvironment } = await import('../dev/dev-env');
    return createDevEnvironment();
  }
  return productionEnvironment();
}

export async function start(target: HTMLElement): Promise<void> {
  const found = await environment();
  if (typeof found === 'string') {
    mount(Gate, { target, props: { kind: found } });
    return;
  }
  const { tg, api } = found;
  bindAppearance(tg, document.documentElement);
  tg.expand();
  tg.disableVerticalSwipes();

  const router = new Router(import.meta.env.BASE_URL);
  router.start(tg.startParam);
  mount(App, { target, props: { context: { api, tg, router, toasts: new Toasts() } } });
  tg.ready();
}
