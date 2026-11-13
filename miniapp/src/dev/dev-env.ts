// Development only: the fake port and the mock backend, with a small panel to drive them.
// Reached through a dynamic import behind import.meta.env.DEV, so it never ships (MA-TEST-2).
import { mount } from 'svelte';
import { MockBackend } from '../mock/backend';
import { FakeTelegramPort } from '../platform/fake';
import { ZERO_INSETS } from '../platform/port';
import DevPanel from './DevPanel.svelte';

/** Long enough to see the skeletons; `?latency=0` turns it off. */
const LATENCY_MS = 300;

function viewport() {
  return { safe: ZERO_INSETS, content: ZERO_INSETS, stableHeight: window.innerHeight };
}

export function createDevEnvironment(): { tg: FakeTelegramPort; api: MockBackend } {
  const query = new URLSearchParams(window.location.search);
  const scheme = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  const tg = new FakeTelegramPort({
    scheme,
    insets: viewport(),
    startParam: query.get('startapp') ?? undefined,
    // The fake answers a "leave without saving?" popup with Discard.
    popupAnswer: 'leave',
  });
  window.addEventListener('resize', () => tg.setInsets(viewport()));
  const api = new MockBackend({ latencyMs: Number(query.get('latency') ?? LATENCY_MS) });

  const host = document.createElement('div');
  document.body.prepend(host);
  mount(DevPanel, { target: host, props: { tg, api } });
  return { tg, api };
}
