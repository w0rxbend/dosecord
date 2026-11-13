import { mount, unmount } from 'svelte';
import type { ApiClient } from '../api/client';
import { createHttpApi } from '../api/http';
import { en } from '../i18n/en';
import { bindAppearance } from '../platform/apply-theme';
import type { TelegramPort } from '../platform/port';
import { createRealTelegramPort } from '../platform/real';
import { Toasts } from '../state/toasts.svelte';
import App from './App.svelte';
import Gate from './Gate.svelte';
import { gateFor, type GateKind } from './gate';
import { Router } from './router.svelte';

/** The session exchange needs the Ed25519 signature, which exists since Bot API 8.0 (3.3). */
const MINIMUM_CLIENT = '8.0';
const API_BASE = '/api/v1';

/** How the application reaches its backend: the session exchange first, then the client. */
export interface Backend {
  /** Resolves when a session exists; rejects with the error the gate screens map. */
  open(): Promise<unknown>;
  readonly client: ApiClient;
}

export interface Environment {
  readonly tg: TelegramPort;
  /** `onSessionEnded` is called when a session is over and cannot be renewed. */
  backend(onSessionEnded: () => void): Backend;
}

type Found = Environment | 'outside' | 'oldClient';

function productionEnvironment(): Found {
  const tg = createRealTelegramPort();
  // Outside Telegram the official script still loads, with an empty initData (TGF-39).
  if (!tg || tg.initData === '') return 'outside';
  if (!tg.isVersionAtLeast(MINIMUM_CLIENT)) return 'oldClient';
  return {
    tg,
    backend: (onSessionEnded) =>
      createHttpApi({
        baseUrl: API_BASE,
        initData: tg.initData,
        colorScheme: tg.theme().scheme,
        platform: tg.platform,
        onSessionEnded,
      }),
  };
}

/**
 * The fake port and the mock backend are chosen by build configuration, not by sniffing the
 * environment (MINI_APP 14.1). The dynamic import sits behind a build-time constant, so a
 * production bundle contains neither (MA-TEST-2).
 */
async function environment(): Promise<Found> {
  if (import.meta.env.DEV) {
    const { createDevEnvironment } = await import('../dev/dev-env');
    return createDevEnvironment();
  }
  return productionEnvironment();
}

/**
 * The launch sequence of MINI_APP 3.2 from step 4: theme, session exchange, then the shell.
 * A failed exchange, and a session that ends later, replace the application with a gate screen.
 */
export async function launch(target: HTMLElement, found: Environment): Promise<void> {
  const { tg } = found;
  let shown: Record<string, unknown> | null = null;

  function show(kind: GateKind): void {
    if (shown) void unmount(shown);
    const retry = { label: en.gate.tryAgain, run: () => void open() };
    const close = { label: en.gate.close, run: () => tg.close() };
    shown = mount(Gate, { target, props: { kind, action: kind === 'offline' ? retry : close } });
  }

  const backend = found.backend(() => show('sessionEnded'));

  async function open(): Promise<void> {
    try {
      await backend.open();
    } catch (cause) {
      return show(gateFor(cause));
    }
    if (shown) void unmount(shown);
    const router = new Router(import.meta.env.BASE_URL);
    router.start(tg.startParam);
    shown = mount(App, { target, props: { context: { api: backend.client, tg, router, toasts: new Toasts() } } });
  }

  bindAppearance(tg, document.documentElement);
  tg.expand();
  tg.disableVerticalSwipes();
  // ready() does not wait for the API (MA-LAUNCH-3).
  tg.ready();
  await open();
}

export async function start(target: HTMLElement): Promise<void> {
  const found = await environment();
  if (typeof found === 'string') {
    mount(Gate, { target, props: { kind: found } });
    return;
  }
  await launch(target, found);
}
