// Development only: the fake port and the mock backend, with a small panel to drive them.
// Reached through a dynamic import behind import.meta.env.DEV, so it never ships (MA-TEST-2).
import { mount } from 'svelte';
import { createHttpApi } from '../api/http';
import type { Environment } from '../app/start';
import { MockBackend } from '../mock/backend';
import { FakeTelegramPort } from '../platform/fake';
import { ZERO_INSETS } from '../platform/port';
import DevPanel from './DevPanel.svelte';

/** Long enough to see the skeletons; `?latency=0` turns it off. */
const LATENCY_MS = 300;

function viewport() {
  return { safe: ZERO_INSETS, content: ZERO_INSETS, stableHeight: window.innerHeight };
}

/**
 * With VITE_DEV_INIT_DATA set, the fake port talks to a real backend through the Vite proxy
 * (vite.config.ts): the value is launch data signed for that backend's development bot token by
 * scripts/dev-init-data.mjs. Without it the mock backend answers.
 */
function devInitData(): string | undefined {
  const value: unknown = import.meta.env.VITE_DEV_INIT_DATA;
  return typeof value === 'string' && value !== '' ? value : undefined;
}

export function createDevEnvironment(): Environment {
  const query = new URLSearchParams(window.location.search);
  const scheme = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  const initData = devInitData();
  const tg = new FakeTelegramPort({
    scheme,
    initData,
    insets: viewport(),
    startParam: query.get('startapp') ?? undefined,
    // The fake answers a "leave without saving?" popup with Discard.
    popupAnswer: 'leave',
  });
  window.addEventListener('resize', () => tg.setInsets(viewport()));
  if (initData !== undefined) {
    return {
      tg,
      backend: (onSessionEnded) =>
        createHttpApi({ baseUrl: '/api/v1', initData, colorScheme: scheme, platform: tg.platform, onSessionEnded }),
    };
  }
  const api = new MockBackend({ latencyMs: Number(query.get('latency') ?? LATENCY_MS) });

  const host = document.createElement('div');
  document.body.prepend(host);
  mount(DevPanel, { target: host, props: { tg, api } });
  return { tg, backend: () => ({ open: () => Promise.resolve(), client: api }) };
}
