// Screenshots of every screen at 390 px, light and dark, for review (`pnpm run e2e:screens`). They are written to
// screenshots/<scheme>/ and are not committed. The guard of support/fixture.ts applies to every screen.
// One launch per scheme: the screens are reached through the app's own router, so the session exchange, limited
// to six a minute per user (ARCHITECTURE API-18), runs once.
import type { Page } from '@playwright/test';
import { expect, test } from './support/fixture';
import { gotoPastNetworkChange } from './support/navigation';
import { pressMainButton, type Scheme } from './support/telegram';

const today = new Date().toISOString().slice(0, 10).replaceAll('-', '');

const SCREENS: readonly (readonly [string, string])[] = [
  ['01-today', '/'],
  ['02-meds', '/meds'],
  ['03-med-new', '/meds/new'],
  ['07-log', '/log'],
  ['08-water', '/log/water'],
  ['09-water-settings', '/log/water/settings'],
  ['10-mood', '/log/mood'],
  ['11-mood-new', '/log/mood/new'],
  ['12-sleep', '/log/sleep'],
  ['13-insights', '/insights'],
  ['14-history', '/insights/history'],
  ['15-day', `/insights/history/${today}`],
  ['16-settings', '/settings'],
  ['17-time-zone', '/settings/tz'],
  ['18-quiet-hours', '/settings/notif'],
  ['19-privacy', '/settings/privacy'],
  ['20-reminders', '/settings/reminders'],
];

/** Moves the running app to an internal path, as a history entry would. */
async function go(page: Page, path: string): Promise<void> {
  await page.evaluate((target) => {
    history.pushState(null, '', `/app${target}`);
    dispatchEvent(new PopStateEvent('popstate'));
  }, path);
}

async function shoot(page: Page, scheme: Scheme, name: string): Promise<void> {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('[aria-busy="true"]')).toHaveCount(0);
  // Let the entry transitions finish.
  await page.waitForTimeout(400);
  await page.screenshot({ path: `screenshots/${scheme}/${name}.png`, fullPage: true });
}

test('some records of today exist before the screens are taken', async ({ app }) => {
  const page = await app.launch({ startParam: 'water' });
  for (let entry = 0; entry < 2; entry += 1) {
    await page.getByRole('button', { name: /^Add \d+ ml$/ }).nth(1).click();
    await expect(page.getByText(/^Added \d+ ml$/)).toBeVisible();
  }
  await go(page, '/log/mood/new');
  await page.getByRole('button', { name: 'More pleasant' }).click();
  await pressMainButton(page);
  await expect(page.getByText('Check-in saved')).toBeVisible();
});

for (const scheme of ['light', 'dark'] as const) {
  test(`every screen, ${scheme} scheme`, async ({ app }) => {
    test.setTimeout(180_000);
    const page = await app.launch({ scheme });
    for (const [name, path] of SCREENS) {
      await go(page, path);
      await shoot(page, scheme, name);
    }

    await go(page, '/meds');
    await page.getByRole('link', { name: /Vitamin D/ }).click();
    const change = page.getByRole('link', { name: 'Change times' });
    await shoot(page, scheme, '04-med-edit');
    const edit = new URL(page.url()).pathname.replace(/^\/app/, '');
    const schedule = (await change.getAttribute('href'))?.replace(/^\/app/, '') ?? '';
    await go(page, schedule);
    await shoot(page, scheme, '05-schedule-edit');
    await go(page, edit.replace(/\/edit$/, '/schedules/new'));
    await shoot(page, scheme, '06-schedule-new');
  });

  test(`gate outside Telegram, ${scheme} scheme`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: scheme });
    await page.route('https://telegram.org/js/telegram-web-app.js', (route) =>
      route.fulfill({ contentType: 'text/javascript', body: '' }),
    );
    await gotoPastNetworkChange(page, '/app/');
    await expect(page.getByRole('heading', { name: 'Dosegram runs inside Telegram.' })).toBeVisible();
    // Outside Telegram the gate follows the system scheme.
    expect(await page.evaluate(() => document.documentElement.dataset.scheme)).toBe(scheme === 'dark' ? 'dark' : undefined);
    await shoot(page, scheme, '21-gate-outside');
  });
}
