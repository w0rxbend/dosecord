// Screenshots of every screen at 360 and 390 px, light and dark, for review (`pnpm run e2e:screens`). They are
// written to screenshots/<width>/<scheme>/ and are not committed. The guard of support/fixture.ts applies to every
// screen. One launch per scheme and width: the screens are reached through the app's own router, so the session
// exchange, limited to six a minute per user (ARCHITECTURE API-18), runs five times in all.
import type { Page } from '@playwright/test';
import { expect, test } from './support/fixture';
import { gotoPastNetworkChange, routeTo } from './support/navigation';
import { SCREENS } from './support/screens';
import { pressMainButton, type Scheme } from './support/telegram';

const WIDTHS = [390, 360] as const;
type Width = (typeof WIDTHS)[number];

async function shoot(page: Page, scheme: Scheme, name: string, width: Width = 390): Promise<void> {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('[aria-busy="true"]')).toHaveCount(0);
  // Let the entry transitions finish.
  await page.waitForTimeout(400);
  await page.screenshot({ path: `screenshots/${width}/${scheme}/${name}.png`, fullPage: true });
}

test('some records of today exist before the screens are taken', async ({ app }) => {
  const page = await app.launch({ startParam: 'water' });
  for (let entry = 0; entry < 2; entry += 1) {
    await page.getByRole('button', { name: /^Add \d+ ml$/ }).nth(1).click();
    await expect(page.getByText(/^Added \d+ ml$/)).toBeVisible();
  }
  await routeTo(page, '/log/mood/new');
  await page.getByRole('button', { name: 'More pleasant' }).click();
  await pressMainButton(page);
  await expect(page.getByText('Check-in saved')).toBeVisible();
});

for (const width of WIDTHS) {
  for (const scheme of ['light', 'dark'] as const) {
    test(`every screen, ${scheme} scheme, ${width} px`, async ({ app }) => {
      test.setTimeout(180_000);
      const page = await app.launch({ scheme });
      await page.setViewportSize({ width, height: 800 });
      for (const [name, path] of SCREENS) {
        await routeTo(page, path);
        await shoot(page, scheme, name, width);
      }

      await routeTo(page, '/meds');
      await page.getByRole('link', { name: /Vitamin D/ }).click();
      const change = page.getByRole('link', { name: 'Change times' });
      await shoot(page, scheme, '04-med-edit', width);
      const edit = new URL(page.url()).pathname.replace(/^\/app/, '');
      const schedule = (await change.getAttribute('href'))?.replace(/^\/app/, '') ?? '';
      await routeTo(page, schedule);
      await shoot(page, scheme, '05-schedule-edit', width);
      await routeTo(page, edit.replace(/\/edit$/, '/schedules/new'));
      await shoot(page, scheme, '06-schedule-new', width);
    });
  }
}

for (const scheme of ['light', 'dark'] as const) {
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
