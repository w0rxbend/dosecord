// ROADMAP G3.12: the user's main paths through the production build against the real application. Each test opens
// the app as Telegram would and fails on any policy violation, page error or foreign request (support/fixture.ts).
import { expect, test } from './support/fixture';
import { chrome, pressMainButton } from './support/telegram';

const MEDICATION = 'Vitamin D';

test('launch: the session exchange answers 201, Today shows the medication, the headers are set', async ({
  app,
  page,
}) => {
  const exchanged = page.waitForResponse((r) => r.url().endsWith('/api/v1/session'));
  await app.launch();
  const response = await exchanged;
  expect(response.status()).toBe(201);
  expect(response.request().method()).toBe('POST');

  await expect(page.getByRole('heading', { level: 1, name: 'Today' })).toBeVisible();
  await expect(page.getByText(MEDICATION).first()).toBeVisible();

  const shell = await page.request.get('/app/');
  expect(shell.headers()['content-security-policy']).toContain("default-src 'none'");
  expect(shell.headers()['content-security-policy']).toContain("frame-ancestors https://web.telegram.org");
  expect(shell.headers()['cache-control']).toBe('no-store');
  expect(shell.headers()['access-control-allow-origin']).toBeUndefined();
});

test('launch keeps no token and no launch data in any browser store (MA-LAUNCH-5)', async ({ app }) => {
  const page = await app.launch();
  await expect(page.getByRole('heading', { level: 1, name: 'Today' })).toBeVisible();

  const stores = await page.evaluate(() => ({
    local: Object.keys(localStorage),
    session: Object.keys(sessionStorage),
    cookie: document.cookie,
  }));
  expect(stores).toEqual({ local: [], session: [], cookie: '' });
  expect(await page.context().cookies()).toEqual([]);
});

test('launch data from another key opens the "session has ended" gate, not the app', async ({ page, baseURL }) => {
  // Without the stub's signature the server refuses the exchange with 401; the app shows gate G-4.
  await page.route('https://telegram.org/js/telegram-web-app.js', (route) =>
    route.fulfill({
      contentType: 'text/javascript',
      body: `window.Telegram = { WebApp: { initData: 'auth_date=1&user=%7B%22id%22%3A1%7D&signature=AAAA&hash=00',
        initDataUnsafe: {}, platform: 'android', colorScheme: 'light', themeParams: {}, viewportStableHeight: 800,
        MainButton: { setText() {}, show() {}, hide() {}, enable() {}, disable() {}, showProgress() {}, hideProgress() {},
          onClick() {}, offClick() {} },
        BackButton: { show() {}, hide() {}, onClick() {}, offClick() {} },
        HapticFeedback: { impactOccurred() {}, notificationOccurred() {}, selectionChanged() {} },
        isVersionAtLeast: () => true, onEvent() {}, offEvent() {}, ready() {}, expand() {}, close() {},
        disableVerticalSwipes() {}, enableClosingConfirmation() {}, disableClosingConfirmation() {},
        showPopup() {} } };`,
    }),
  );
  await page.goto(`${baseURL}/app/`);
  await expect(page.getByRole('heading', { name: 'This session has ended.' })).toBeVisible();
});

test('log water from the deep link: the amount is recorded and shown', async ({ app }) => {
  const page = await app.launch({ startParam: 'water' });
  await expect(page.getByRole('heading', { level: 1, name: 'Water' })).toBeVisible();

  const add = page.getByRole('button', { name: /^Add \d+ ml$/ }).first();
  const label = (await add.getAttribute('aria-label')) ?? '';
  const amount = Number(/\d+/.exec(label)?.[0]);
  const saved = page.waitForResponse((r) => r.url().endsWith('/api/v1/hydration/entries') && r.request().method() === 'POST');
  await add.click();
  expect((await saved).status()).toBe(201);
  await expect(page.getByText(`Added ${amount} ml`)).toBeVisible();
  await expect(page.getByRole('button', { name: new RegExp(`^Remove ${amount} ml at`) })).toBeVisible();
});

test('mood check-in: the slider is moved, Save on the bottom button records it', async ({ app }) => {
  const page = await app.launch({ startParam: 'moodnew' });
  await expect(page.getByRole('heading', { level: 1, name: 'How are you right now?' })).toBeVisible();
  await expect
    .poll(async () => (await chrome(page)).main)
    .toMatchObject({ text: 'Save check-in', visible: true, enabled: false });

  await page.getByRole('button', { name: 'More pleasant' }).click();
  await expect.poll(async () => (await chrome(page)).main.enabled).toBe(true);
  const saved = page.waitForResponse((r) => r.url().endsWith('/api/v1/mood/checkins'));
  await pressMainButton(page);
  expect((await saved).status()).toBe(201);
  await expect(page.getByText('Check-in saved')).toBeVisible();
});

test('edit a medication time: the schedule gets a new revision and the list shows the new time', async ({ app }) => {
  const page = await app.launch({ startParam: 'meds' });
  await page.getByRole('link', { name: new RegExp(MEDICATION) }).click();
  await page.getByRole('link', { name: 'Change times' }).click();
  await expect(page.getByRole('heading', { level: 1, name: `Schedule for ${MEDICATION}` })).toBeVisible();

  await page.getByLabel(/^Time 1/).fill('08:30');
  await expect.poll(async () => (await chrome(page)).main.enabled).toBe(true);
  const revised = page.waitForResponse((r) => /\/api\/v1\/schedules\/[^/]+\/revisions$/.test(r.url()));
  await pressMainButton(page);
  expect((await revised).status()).toBe(201);

  await expect(page.getByRole('heading', { level: 1, name: 'Medications' })).toBeVisible();
  await expect(page.getByText(/08:30/).first()).toBeVisible();
});

test('reminders: a preset is turned on, then off again', async ({ app }) => {
  const page = await app.launch({ startParam: 'reminders' });
  await expect(page.getByRole('heading', { level: 1, name: 'Reminders' })).toBeVisible();

  const first = page.locator('fieldset').first();
  await first.getByRole('radio').first().check();
  const on = page.waitForResponse((r) => r.url().endsWith('/api/v1/reminders/plans') && r.request().method() === 'POST');
  await first.getByRole('button', { name: 'Turn on' }).click();
  expect((await on).ok()).toBe(true);
  await expect(page.getByText(/ is on$/)).toBeVisible();

  const off = page.waitForResponse((r) => /\/api\/v1\/reminders\/plans\/[^/]+$/.test(r.url()));
  await first.getByRole('button', { name: 'Turn off' }).click();
  expect((await off).ok()).toBe(true);
  await expect(page.getByText('Turned off')).toBeVisible();
});
