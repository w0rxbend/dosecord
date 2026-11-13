// The suite's test function: every test counts content-security-policy violations, page errors and requests to any
// origin except the app's own and telegram.org as failures (ARCHITECTURE API-20, ROADMAP G3.3 and G3.12).
import { test as base, expect, type Page } from '@playwright/test';
import { installTelegram, type LaunchOptions } from './telegram';

type Watched = Window & { __violations?: string[] };

export interface Launcher {
  /** Opens the app the way Telegram does: the stub script, fresh launch data, then /app/. */
  launch(options?: LaunchOptions): Promise<Page>;
}

export const test = base.extend<{ app: Launcher }>({
  app: async ({ page, baseURL }, use) => {
    const own = new URL(baseURL ?? '').origin;
    const problems: string[] = [];
    await page.addInitScript(() => {
      document.addEventListener('securitypolicyviolation', (event) => {
        const watched = window as Watched;
        watched.__violations = [...(watched.__violations ?? []), `${event.violatedDirective} ${event.blockedURI}`];
      });
    });
    page.on('pageerror', (error) => problems.push(`page error: ${error.message}`));
    page.on('console', (message) => {
      if (message.type() === 'error') problems.push(`console: ${message.text()}`);
    });
    page.on('request', (request) => {
      const origin = new URL(request.url()).origin;
      if (origin !== own && origin !== 'https://telegram.org') problems.push(`request to ${origin}`);
    });

    await use({
      launch: async (options) => {
        await installTelegram(page, options);
        await page.goto('/app/');
        return page;
      },
    });

    const violations = await page.evaluate(() => (window as Watched).__violations ?? []).catch(() => []);
    expect(violations, 'content security policy violations').toEqual([]);
    expect(problems, 'page errors, console errors and foreign requests').toEqual([]);
  },
});

export { expect };
