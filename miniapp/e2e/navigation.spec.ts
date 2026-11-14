// The navigation helper of the suite, on a stand-in page: only Chromium's network-change error is made again.
import { test, expect } from '@playwright/test';
import { gotoPastNetworkChange } from './support/navigation';

function failing(messages: string[]) {
  const visits: string[] = [];
  return {
    visits,
    page: {
      goto: async (url: string) => {
        visits.push(url);
        const message = messages.shift();
        if (message) throw new Error(message);
        return null;
      },
    },
  };
}

test('a navigation aborted by a network change is made again', async () => {
  const { page, visits } = failing(['page.goto: net::ERR_NETWORK_CHANGED at http://127.0.0.1/app/']);
  await gotoPastNetworkChange(page, '/app/');
  expect(visits).toEqual(['/app/', '/app/']);
});

test('any other navigation error fails at once', async () => {
  const { page, visits } = failing(['page.goto: net::ERR_CONNECTION_REFUSED at http://127.0.0.1/app/']);
  await expect(gotoPastNetworkChange(page, '/app/')).rejects.toThrow('ERR_CONNECTION_REFUSED');
  expect(visits).toEqual(['/app/']);
});

test('a network change that keeps happening fails after three attempts', async () => {
  const changed = 'page.goto: net::ERR_NETWORK_CHANGED';
  const { page, visits } = failing([changed, changed, changed, changed]);
  await expect(gotoPastNetworkChange(page, '/app/')).rejects.toThrow('ERR_NETWORK_CHANGED');
  expect(visits).toHaveLength(3);
});
