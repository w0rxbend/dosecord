// The https rehearsal of docs/DEPLOY_MINIAPP.md (`pnpm run e2e:https`): what only the public origin shows, through the
// TLS proxy of docker/proxy/Caddyfile. The headers reach the browser unchanged, the proxy forwards /app/ and /api/
// and nothing else, plain http is redirected, a large body is refused, forged launch data is refused, and only
// Telegram Web may frame the app.
import { expect, test, type Page } from '@playwright/test';
import { hmacHash, TELEGRAM_USER_ID } from './support/launch-data';
import { proxyPorts } from './support/origin';
import { installTelegram } from './support/telegram';

const ports = proxyPorts(process.env);
if (!ports) throw new Error('origin.spec.ts runs with E2E_ORIGIN=https only (pnpm run e2e:https)');
const origin = `https://localhost:${ports.https}`;

const POLICY = [
  "default-src 'none'",
  "script-src 'self' https://telegram.org",
  "style-src 'self'",
  "img-src 'self' blob: data:",
  "font-src 'self'",
  "connect-src 'self'",
  "base-uri 'none'",
  "form-action 'none'",
  "object-src 'none'",
  'frame-ancestors https://web.telegram.org',
  "require-trusted-types-for 'script'",
].join('; ');

/** Fresh launch fields of the suite's user, unsigned. */
function launchFields(): Record<string, string> {
  return {
    auth_date: String(Math.floor(Date.now() / 1000)),
    query_id: 'rehearsal',
    user: JSON.stringify({ id: TELEGRAM_USER_ID, first_name: 'Test', language_code: 'en' }),
  };
}

test('the shell over TLS carries every security header once, and no CORS header', async ({ request }) => {
  const shell = await request.get('/app/');
  expect(shell.status()).toBe(200);
  const headers = shell.headersArray();
  const all = (name: string) => headers.filter((h) => h.name.toLowerCase() === name).map((h) => h.value);
  expect(all('content-security-policy')).toEqual([POLICY]);
  expect(all('strict-transport-security')).toEqual(['max-age=31536000']);
  expect(all('x-content-type-options')).toEqual(['nosniff']);
  expect(all('referrer-policy')).toEqual(['no-referrer']);
  expect(all('cross-origin-resource-policy')).toEqual(['same-origin']);
  expect(all('cache-control')).toEqual(['no-store']);
  expect(all('access-control-allow-origin')).toEqual([]);
  expect(all('x-frame-options')).toEqual([]);

  // The built files are cached for good and compressed by the proxy.
  const script = /src="(\/app\/assets\/[^"]+\.js)"/.exec(await shell.text())?.[1];
  expect(script, 'the shell names its script').toBeDefined();
  const asset = await request.get(script!, { headers: { 'accept-encoding': 'gzip, zstd' } });
  expect(asset.status()).toBe(200);
  expect(asset.headers()['cache-control']).toBe('public, max-age=31536000, immutable');
  expect(asset.headers()['content-encoding']).toMatch(/^(gzip|zstd)$/);
  expect(asset.headers()['content-security-policy']).toBe(POLICY);
});

test('the proxy forwards /app/ and /api/ only', async ({ request }) => {
  for (const path of ['/', '/healthz', '/readyz', '/metrics', '/tg/webhook/rehearsal-path-secret', '/application']) {
    expect((await request.get(path)).status(), path).toBe(404);
  }
  const api = await request.get('/api/v1/today');
  expect(api.status()).toBe(401);
  expect(api.headers()['content-type']).toContain('application/problem+json');
});

test('plain http is redirected to the https origin', async ({ request }) => {
  const plain = await request.get(`http://localhost:${ports.http}/app/`, { maxRedirects: 0 });
  expect([301, 308]).toContain(plain.status());
  // The same host and path; Caddy names no port in the redirect, which on a public host is 443.
  expect(plain.headers()['location']).toMatch(/^https:\/\/localhost(:\d+)?\/app\/$/);
});

test('a body above 64 kB is refused before it reaches the application', async ({ request }) => {
  const large = await request.post('/api/v1/session', {
    headers: { 'content-type': 'application/json' },
    data: JSON.stringify({ initData: 'x'.repeat(70 * 1024) }),
  });
  expect(large.status()).toBe(413);
});

test('launch data with a forged signature, or with the HMAC hash alone, opens no session', async ({ request }) => {
  const fields = launchFields();
  const forged = new URLSearchParams({ ...fields, signature: Buffer.alloc(64, 7).toString('base64url') });
  forged.set('hash', hmacHash(Object.fromEntries(forged)));
  const refused = await request.post('/api/v1/session', { data: { initData: forged.toString() } });
  expect(refused.status()).toBe(401);
  expect(refused.headers()['content-type']).toContain('application/problem+json');
  expect((await refused.json()).code).toBe('unauthenticated');

  // A correct HMAC with the test bot token and no signature: the fallback is off, as production requires (CFG-01).
  const hashOnly = new URLSearchParams({ ...fields, hash: hmacHash(fields) });
  const old = await request.post('/api/v1/session', { data: { initData: hashOnly.toString() } });
  expect(old.status()).toBe(401);
  expect((await old.json()).code).toBe('client_too_old');
});

/**
 * A page of `parent` that frames the app, served by the test in place of that site. The rehearsal's origin is on the
 * loopback interface, which Chromium lets a public site reach only with the local-network permission; a public
 * origin needs none.
 */
async function framedBy(page: Page, parent: string): Promise<void> {
  await page.context().grantPermissions(['local-network-access'], { origin: parent });
  await page.route(`${parent}/**`, (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: `<!doctype html><title>frame</title><iframe src="${origin}/app/" width="390" height="800"></iframe>`,
    }),
  );
  await page.goto(`${parent}/k/`);
}

test('Telegram Web frames the app, which opens Today from signed launch data', async ({ page }) => {
  await installTelegram(page);
  const exchanged = page.waitForResponse((r) => r.url() === `${origin}/api/v1/session`);
  await framedBy(page, 'https://web.telegram.org');
  expect((await exchanged).status()).toBe(201);
  const app = page.frameLocator('iframe');
  await expect(app.getByRole('heading', { level: 1, name: 'Today' })).toBeVisible();
});

test('any other site is refused the frame by frame-ancestors', async ({ page }) => {
  const refusals: string[] = [];
  page.on('console', (message) => {
    if (message.text().includes('frame-ancestors')) refusals.push(message.text());
  });
  await installTelegram(page);
  let exchanged = false;
  page.on('request', (r) => {
    if (r.url().endsWith('/api/v1/session')) exchanged = true;
  });
  await framedBy(page, 'https://example.org');
  await expect.poll(() => refusals.length).toBeGreaterThan(0);
  expect(refusals[0]).toContain('https://web.telegram.org');
  expect(exchanged, 'a refused frame runs no script').toBe(false);
});
