// Chromium aborts a navigation with net::ERR_NETWORK_CHANGED when the host gains or loses a network interface, which
// Docker does when it starts or stops a container. That error says nothing about the app, so a navigation that fails
// with it is made again, a few times at most. Every other failure, and every content security policy violation, still
// fails the test at once: Playwright's own retries stay off.
import type { Page } from '@playwright/test';

const NETWORK_CHANGED = 'net::ERR_NETWORK_CHANGED';
const ATTEMPTS = 3;

type Navigator = Pick<Page, 'goto'>;

export async function gotoPastNetworkChange(page: Navigator, url: string): Promise<void> {
  for (let attempt = 1; ; attempt++) {
    try {
      await page.goto(url);
      return;
    } catch (error) {
      const networkChanged = error instanceof Error && error.message.includes(NETWORK_CHANGED);
      if (!networkChanged || attempt >= ATTEMPTS) throw error;
    }
  }
}

/** Moves the running app to an internal path through its own router, as a history entry would. */
export async function routeTo(page: Pick<Page, 'evaluate'>, path: string): Promise<void> {
  await page.evaluate((target) => {
    history.pushState(null, '', `/app${target}`);
    dispatchEvent(new PopStateEvent('popstate'));
  }, path);
}
