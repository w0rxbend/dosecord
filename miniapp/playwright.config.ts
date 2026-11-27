// Browser end-to-end suite (ROADMAP G3.12): the production build of the Mini App against the real application on a
// throwaway PostgreSQL, under the application's own security headers, with a stand-in for Telegram's script whose
// launch data is signed by the suite's key. `pnpm run e2e` runs the flows, then the accessibility scan and the walk
// on fresh backends; `pnpm run e2e:screens` takes the screenshots of every screen at 360 and 390 px in both schemes.
// With E2E_ORIGIN=https (`pnpm run e2e:https`) the same suite runs through the TLS proxy of docker/proxy/Caddyfile on
// https://localhost, and the project `origin` checks what only that origin shows. Needs Docker and a JDK.
import { defineConfig } from '@playwright/test';
import { fileURLToPath } from 'node:url';
import { BOT_ID, PUBLIC_KEY_HEX, TELEGRAM_USER_ID } from './e2e/support/launch-data';
import { proxyPorts } from './e2e/support/origin';

const port = Number(process.env.E2E_PORT ?? 18481);
const dist = fileURLToPath(new URL('./dist', import.meta.url));
const proxy = proxyPorts(process.env);

const backend = {
  command: `node e2e/backend.mjs ${port} ${dist} ${PUBLIC_KEY_HEX} ${BOT_ID} ${TELEGRAM_USER_ID}`,
  url: `http://127.0.0.1:${port}/app/`,
  timeout: 300_000,
  reuseExistingServer: false,
  stdout: 'pipe' as const,
  gracefulShutdown: { signal: 'SIGTERM' as const, timeout: 30_000 },
};

export default defineConfig({
  testDir: 'e2e',
  outputDir: 'test-results',
  // One backend and one account: the specs run in order, in one worker.
  workers: 1,
  fullyParallel: false,
  forbidOnly: true,
  // No retries: every test fails on a content security policy violation, and a violation that shows once must fail
  // the run, not pass on a second attempt (stability review of 2026-11-14). A navigation that Chromium aborts with
  // ERR_NETWORK_CHANGED, when Docker adds or removes an interface, is made again by e2e/support/navigation.ts instead.
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  reporter: [['list']],
  use: {
    baseURL: proxy ? `https://localhost:${proxy.https}` : `http://127.0.0.1:${port}`,
    // The rehearsal's certificate comes from Caddy's local authority, which the browser does not know.
    ignoreHTTPSErrors: proxy !== undefined,
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 2,
    isMobile: true,
    hasTouch: true,
    timezoneId: 'UTC',
    locale: 'en-GB',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'flows', testMatch: ['navigation.spec.ts', 'flows.spec.ts'] },
    // Its own run and backend: the flows use the six session exchanges a minute that one user is allowed (API-18).
    { name: 'a11y', testMatch: 'a11y.spec.ts' },
    // Its own run and backend for the same reason: sleep, a meal with words, the share builder and the export.
    { name: 'walk', testMatch: 'walk.spec.ts' },
    { name: 'screens', testMatch: 'screens.spec.ts' },
    ...(proxy ? [{ name: 'origin', testMatch: 'origin.spec.ts' }] : []),
  ],
  webServer: proxy
    ? [
        backend,
        {
          command: `node e2e/proxy.mjs 127.0.0.1:${port} ${proxy.https} ${proxy.http}`,
          url: `https://localhost:${proxy.https}/app/`,
          ignoreHTTPSErrors: true,
          timeout: 120_000,
          reuseExistingServer: false,
          stdout: 'pipe' as const,
          gracefulShutdown: { signal: 'SIGTERM' as const, timeout: 30_000 },
        },
      ]
    : backend,
});
