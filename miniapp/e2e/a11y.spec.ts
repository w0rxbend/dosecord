// MINI_APP section 9 (MA-A11Y-3): an automated scan of every screen with axe-core, in both schemes, against the WCAG
// 2.2 A and AA rules. A serious or critical finding fails the run; the manual passes of section 9 stay a release gate.
// One launch for both schemes: the screens are reached through the app's own router and the theme is switched as
// Telegram switches it, because session exchanges are rate-limited (ARCHITECTURE API-18).
import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';
import { expect, test } from './support/fixture';
import { routeTo } from './support/navigation';
import { SCREENS } from './support/screens';
import { setScheme } from './support/telegram';

const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];

/** The serious and critical findings of the visible screen, one line each: rule, impact and the first target. */
async function findings(page: Page, screen: string): Promise<string[]> {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('[aria-busy="true"]')).toHaveCount(0);
  const result = await new AxeBuilder({ page }).withTags(TAGS).analyze();
  return result.violations
    .filter((violation) => violation.impact === 'serious' || violation.impact === 'critical')
    .map((violation) => `${screen}: ${violation.id} (${violation.impact}) at ${violation.nodes[0]?.target.join(' ') ?? '?'}`);
}

test('no serious accessibility finding on any screen, in the light and the dark scheme', async ({ app }) => {
  test.setTimeout(240_000);
  const page = await app.launch({ scheme: 'light' });
  const found: string[] = [];
  for (const scheme of ['light', 'dark'] as const) {
    await setScheme(page, scheme);
    await expect.poll(() => page.evaluate(() => document.documentElement.dataset.scheme ?? 'light')).toBe(scheme);
    for (const [name, path] of SCREENS) {
      await routeTo(page, path);
      found.push(...(await findings(page, `${scheme} ${name}`)));
    }
  }
  expect(found).toEqual([]);
});
