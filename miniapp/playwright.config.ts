// Browser end-to-end suite (ROADMAP G3.12): the production build of the Mini App against the real application on a
// throwaway PostgreSQL, under the application's own security headers, with a stand-in for Telegram's script whose
// launch data is signed by the suite's key. `pnpm run e2e` runs the flows; `pnpm run e2e:screens` takes the
// screenshots of every screen at 390 px in both schemes. Needs Docker and a JDK.
import { defineConfig } from '@playwright/test';
import { fileURLToPath } from 'node:url';
import { BOT_ID, PUBLIC_KEY_HEX, TELEGRAM_USER_ID } from './e2e/support/launch-data';

const port = Number(process.env.E2E_PORT ?? 18481);
const dist = fileURLToPath(new URL('./dist', import.meta.url));

export default defineConfig({
  testDir: 'e2e',
  outputDir: 'test-results',
  // One backend and one account: the specs run in order, in one worker.
  workers: 1,
  fullyParallel: false,
  forbidOnly: true,
  // No retries: every test fails on a content security policy violation, and a violation that shows once must fail
  // the run, not pass on a second attempt (stability review of 2026-11-14).
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  reporter: [['list']],
  use: {
    baseURL: `http://127.0.0.1:${port}`,
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 2,
    isMobile: true,
    hasTouch: true,
    timezoneId: 'UTC',
    locale: 'en-GB',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'flows', testMatch: 'flows.spec.ts' },
    { name: 'screens', testMatch: 'screens.spec.ts' },
  ],
  webServer: {
    command: `node e2e/backend.mjs ${port} ${dist} ${PUBLIC_KEY_HEX} ${BOT_ID} ${TELEGRAM_USER_ID}`,
    url: `http://127.0.0.1:${port}/app/`,
    timeout: 300_000,
    reuseExistingServer: false,
    stdout: 'pipe',
    gracefulShutdown: { signal: 'SIGTERM', timeout: 30_000 },
  },
});
