// ROADMAP G3.12: the user's main paths through the production build against the real application. Each test opens
// the app as Telegram would and fails on any policy violation, page error or foreign request (support/fixture.ts).
import type { Page } from '@playwright/test';
import { expect, test } from './support/fixture';
import { gotoPastNetworkChange, routeTo } from './support/navigation';
import { answerPopup, chrome, pressBackButton, pressMainButton } from './support/telegram';

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
  // The account has never saved its day: Today offers set-up (MINI_APP S-19).
  await expect(page.getByRole('region', { name: 'Finish setting up' })).toBeVisible();

  const shell = await page.request.get('/app/');
  expect(shell.headers()['content-security-policy']).toContain("default-src 'none'");
  expect(shell.headers()['content-security-policy']).toContain("frame-ancestors https://web.telegram.org");
  expect(shell.headers()['cache-control']).toBe('no-store');
  expect(shell.headers()['access-control-allow-origin']).toBeUndefined();

  // ROADMAP G3.7: a due dose is answered with one tap through the chat's use case, and Undo takes it back. In this
  // test, not its own: the session exchange allows six launches a minute (ARCHITECTURE API-18).
  // Only Iron's controls: between 21:00 and its miss deadline (UTC) Vitamin D's evening dose is due too.
  const due = page.getByRole('region', { name: 'Due now' });
  const iron = () => page.getByRole('region', { name: 'Due now' }).getByRole('group', { name: 'Actions for Iron' });
  await expect(due.getByText('Iron')).toBeVisible();
  const answered = page.waitForResponse((r) => /\/api\/v1\/occurrences\/[^/]+\/actions$/.test(r.url()));
  await iron().getByRole('button', { name: 'Taken' }).click();
  const answer = await answered;
  expect(answer.status()).toBe(200);
  expect(answer.request().headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
  await expect(page.getByText(/^Marked taken at \d\d:\d\d$/)).toBeVisible();
  await expect(iron()).toHaveCount(0);
  const undone = page.waitForResponse((r) => /\/api\/v1\/occurrences\/[^/]+\/actions$/.test(r.url()));
  await page.getByRole('button', { name: 'Undo' }).click();
  expect((await undone).status()).toBe(200);
  await expect(iron().getByRole('button', { name: 'Taken' })).toBeVisible();

  // A meal in one tap from Today (MINI_APP S-08 behaviour 1), and Undo voids it.
  const food = page.getByRole('region', { name: 'Food' });
  const logged = page.waitForResponse((r) => r.url().endsWith('/api/v1/meals') && r.request().method() === 'POST');
  await food.getByRole('button', { name: 'Log snack now' }).click();
  expect((await logged).status()).toBe(201);
  await expect(page.getByText('Snack logged')).toBeVisible();
  const voided = page.waitForResponse((r) => /\/api\/v1\/meals\/[^/]+$/.test(r.url()) && r.request().method() === 'DELETE');
  await page.getByRole('button', { name: 'Undo' }).click();
  expect((await voided).ok()).toBe(true);
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
  await gotoPastNetworkChange(page, `${baseURL}/app/`);
  await expect(page.getByRole('heading', { name: 'This session has ended.' })).toBeVisible();
});

test('log water from the deep link: the amount is recorded, shown, and counted in the month', async ({ app }) => {
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

  // The month and the patterns of the Insights screen come from the real backend in its own words.
  await pressBackButton(page);
  await expect(page.getByRole('heading', { level: 1, name: 'Log' })).toBeVisible();
  const month = page.waitForResponse((r) => /\/api\/v1\/insights\/month$/.test(r.url()));
  const patterns = page.waitForResponse((r) => r.url().endsWith('/api/v1/insights/correlations'));
  await page.getByRole('link', { name: 'Insights' }).click();
  expect((await month).status()).toBe(200);
  expect((await patterns).status()).toBe(200);
  await expect(page.getByRole('heading', { level: 2, name: 'Patterns in your records' })).toBeVisible();
  await expect(page.getByText(/^Water: 13 more days with records for the month's figures \(1 of 14\)$/)).toBeVisible();
  const previous = page.waitForResponse((r) => /\/api\/v1\/insights\/month\?month=\d{4}-\d{2}$/.test(r.url()));
  await page.getByRole('button', { name: 'Previous month' }).click();
  expect((await previous).status()).toBe(200);
  await expect(page.getByRole('button', { name: 'Next month' })).toBeVisible();

  // The dashboard (MINI_APP 7.11): every block names the previous period; 90 days are drawn in weeks; the day picked
  // on a chart of days opens Day detail.
  const water = page.getByRole('region', { name: 'Water' });
  await expect(water.getByText(/^Previous 30 days: /)).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'At a glance' })).toBeVisible();
  const quarter = page.waitForResponse((r) => r.url().endsWith('/api/v1/insights/overview?range=90'));
  await page.getByLabel('90 days').check();
  expect((await quarter).status()).toBe(200);
  await expect(water.getByText('Water per week', { exact: true })).toBeVisible();
  await expect(water.getByText(/^Previous 90 days: /)).toBeVisible();
  // The Year tab (supervisor decision, 2026-11-28): the year of /stats year from the real backend, a table of months
  // per area and the weeks of water, the current week holding the water just added.
  const year = page.waitForResponse((r) => r.url().endsWith('/api/v1/insights/year'));
  await page.getByRole('group', { name: 'Period' }).getByLabel('Year').check();
  expect((await year).status()).toBe(200);
  const waterYear = page.getByRole('region', { name: 'Water, a day with records' });
  await expect(waterYear.getByRole('table', { name: 'Water, a day with records: By month' })).toBeVisible();
  await expect(waterYear.getByRole('slider', { name: 'Days with a record, week by week: values' })).toHaveAttribute(
    'aria-valuetext',
    /: [1-7] of [1-7] days with a record$/,
  );
  const week = page.waitForResponse((r) => r.url().endsWith('/api/v1/insights/overview?range=7'));
  await page.getByLabel('7 days').check();
  expect((await week).status()).toBe(200);
  await expect(water.getByText('Water per day', { exact: true })).toBeVisible();
  // The last mark is today, with the water just added.
  await water.getByRole('link', { name: 'Open this day' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Day' })).toBeVisible();
  await expect(page).toHaveURL(/\/app\/insights\/history\/\d{8}$/);

  // Two taps on one amount, then the check-in screen through the router. Two pending rows of one amount in one minute
  // once shared a key, and the keyed list failed the screen. The reload after the first tap is held until the second
  // tap, so the second pending row is added beside the first. In this test, not its own: the session exchange allows
  // six launches a minute (ARCHITECTURE API-18).
  await routeTo(page, '/log/water');
  const again = page.getByRole('button', { name: /^Add \d+ ml$/ }).nth(1);
  await expect(again).toBeVisible();
  let release = (): void => undefined;
  const held = new Promise<void>((resolve) => (release = resolve));
  await page.route(/\/api\/v1\/hydration\/summary$/, async (route) => {
    await held;
    await route.continue();
  });
  const first = page.waitForResponse((r) => r.url().endsWith('/api/v1/hydration/entries') && r.request().method() === 'POST');
  await again.click();
  await first;
  await again.click();
  release();
  await page.unroute(/\/api\/v1\/hydration\/summary$/);
  await expect(page.getByRole('button', { name: /^Remove \d+ ml at/ })).toHaveCount(3);
  await expect(page.getByText('Something went wrong on this screen')).toHaveCount(0);
  await routeTo(page, '/log/mood/new');
  await expect(page.getByRole('heading', { level: 1, name: 'How are you right now?' })).toBeVisible();
  await expect(page.getByText('Something went wrong on this screen')).toHaveCount(0);
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

test('reminders: a preset is turned on, then off again; the language is switched and back; imports', async ({ app }) => {
  // On the desktop, where the app also offers the import of a device's health export (MINI_APP MA-REP-6).
  const page = await app.launch({ startParam: 'reminders', platform: 'tdesktop' });
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

  // Settings complete and set-up (MA-SET-11, S-19): the day saved on its own page counts as done in set-up.
  await pressBackButton(page);
  await expect(page.getByRole('heading', { level: 1, name: 'Settings' })).toBeVisible();
  await page.getByRole('link', { name: /^Set up Dosegram/ }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Set up Dosegram' })).toBeVisible();
  const day = page.getByRole('listitem').filter({ has: page.getByRole('heading', { name: 'Your day' }) });
  await expect(day.getByText('To do')).toBeVisible();
  await day.getByRole('button', { name: 'Set your day' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Your day' })).toBeVisible();
  await page.getByLabel('Wake', { exact: true }).fill('06:45');
  const saved = page.waitForResponse((r) => r.url().endsWith('/api/v1/reminders/day') && r.request().method() === 'PATCH');
  await page.getByRole('button', { name: 'Save day' }).click();
  expect((await saved).ok()).toBe(true);
  await expect(page.getByRole('heading', { level: 1, name: 'Set up Dosegram' })).toBeVisible();
  await expect(day.getByText('Done')).toBeVisible();
  await pressBackButton(page);
  await expect(page.getByRole('heading', { level: 1, name: 'Settings' })).toBeVisible();

  // Language (ROADMAP G2.22): Ukrainian is saved on an account still on the default UTC zone, then English again,
  // because every test of the suite shares the account. In this test: the session exchange allows six launches a
  // minute (API-18).
  const toUkrainian = page.waitForResponse((r) => r.url().endsWith('/api/v1/settings') && r.request().method() === 'PATCH');
  await page.getByRole('combobox', { name: 'Language' }).selectOption('uk');
  expect((await toUkrainian).status()).toBe(200);
  await expect(page.getByRole('heading', { level: 1, name: 'Налаштування' })).toBeVisible();
  const toEnglish = page.waitForResponse((r) => r.url().endsWith('/api/v1/settings') && r.request().method() === 'PATCH');
  await page.getByRole('combobox', { name: 'Мова' }).selectOption('en');
  expect((await toEnglish).status()).toBe(200);
  await expect(page.getByRole('heading', { level: 1, name: 'Settings' })).toBeVisible();

  // The switches of the chat's /settings that live apart from the account row (BOT_UX 17): off, then on again,
  // because every test of the suite shares the account.
  const reports = page.getByRole('region', { name: 'Reports and notes' });
  for (const checked of [false, true]) {
    const notes = page.waitForResponse((r) => r.url().endsWith('/api/v1/settings') && r.request().method() === 'PATCH');
    await reports.getByRole('switch', { name: /Notes after updates/ }).setChecked(checked);
    expect((await notes).status()).toBe(200);
    await expect(reports.getByRole('switch', { name: /Notes after updates/ })).toBeChecked({ checked });
  }

  // Deletion (MA-SET-7, ROADMAP G2.17): a danger button, a native confirmation, then the confirmation in the chat
  // (MA-STEP-1). The suite shares one account, so only the request of one area is made, and it deletes nothing
  // before the tap in the chat.
  await page.getByRole('link', { name: /^Delete data/ }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Delete data' })).toBeVisible();
  await page.getByLabel('Mood').check();
  await page.getByRole('button', { name: "Delete this area's data" }).click();
  await expect.poll(async () => (await chrome(page)).popups.at(-1)).toBe('Delete all mood records? This cannot be undone.');
  const deletion = page.waitForResponse((r) => r.url().endsWith('/api/v1/domains/mood/data'));
  await answerPopup(page, 'delete');
  expect((await deletion).status()).toBe(202);
  await expect(page.getByRole('status').filter({ hasText: 'Confirm in your chat with Dosegram' })).toBeVisible();
  await pressBackButton(page);
  await expect(page.getByRole('heading', { level: 1, name: 'Settings' })).toBeVisible();

  await page.getByRole('link', { name: /^Export everything/ }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Reports and export' })).toBeVisible();

  await importsFromAFile(page);
});

/**
 * Imports (ROADMAP G10.5, G10.7), from the reports screen: an Apple Health export and a CSV file are previewed, imported
 * once and removed. A step of the last test, because the session exchange allows six launches a minute (API-18).
 */
async function importsFromAFile(page: Page): Promise<void> {
  // Ten days back, so no entry another test of the shared account made can overlap.
  const day = new Date(Date.now() - 10 * 86_400_000).toISOString().slice(0, 10);
  const before = new Date(Date.parse(`${day}T00:00:00Z`) - 86_400_000).toISOString().slice(0, 10);
  const record = (type: string, start: string, end: string, extra: string) =>
    `<Record type="${type}" sourceName="Phone" startDate="${start} +0000" endDate="${end} +0000" ${extra}/>`;
  const padding = Array.from({ length: 2000 }, (_, i) =>
    record('HKQuantityTypeIdentifierStepCount', `${day} 12:00:00`, `${day} 12:01:00`, `unit="count" value="${i}"`),
  );
  const xml = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<!DOCTYPE HealthData [<!ELEMENT HealthData (Record*)>]>',
    '<HealthData locale="en_GB">',
    record('HKQuantityTypeIdentifierDietaryWater', `${day} 08:00:00`, `${day} 08:00:00`, 'unit="mL" value="250"'),
    record('HKQuantityTypeIdentifierBodyMass', `${day} 07:30:00`, `${day} 07:30:00`, 'unit="kg" value="70"'),
    record('HKCategoryTypeIdentifierSleepAnalysis', `${before} 22:30:00`, `${day} 07:10:00`, 'value="HKCategoryValueSleepAnalysisInBed"'),
    record('HKCategoryTypeIdentifierSleepAnalysis', `${before} 23:00:00`, `${day} 03:00:00`, 'value="HKCategoryValueSleepAnalysisAsleepCore"'),
    record('HKCategoryTypeIdentifierSleepAnalysis', `${day} 03:10:00`, `${day} 07:00:00`, 'value="HKCategoryValueSleepAnalysisAsleepDeep"'),
    ...padding,
    '</HealthData>',
  ].join('\n');
  const healthFile = { name: 'export.xml', mimeType: 'text/xml', buffer: Buffer.from(xml) };

  // Imports (ROADMAP G10.5, G10.7), from the reports screen; Apple Health exports are offered on the desktop.
  await page.getByRole('link', { name: /^Import from a file/ }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Import from a file' })).toBeVisible();
  // Every body the app sends while importing is kept: the raw file must never be one of them (AC-IMX-9).
  const sent: string[] = [];
  page.on('request', (request) => {
    if (request.url().includes('/api/v1/imports')) sent.push(request.postData() ?? '');
  });

  // Only sleep is selected: the drink and the body mass stay on the device and are counted as left out.
  await page.getByLabel('Water', { exact: true }).uncheck();
  await page.locator('input[type="file"][accept=".zip,.xml"]').setInputFiles(healthFile);
  const preview = page.getByRole('region', { name: 'Preview' });
  await expect(preview).toContainText('Sleep: 1 new, 0 already recorded');
  await expect(preview).toContainText('2003 records of other types are left out.');
  const committed = page.waitForResponse((r) => /\/api\/v1\/imports\/[^/]+\/commit$/.test(r.url()));
  await preview.getByRole('button', { name: 'Import these records' }).click();
  expect((await committed).status()).toBe(200);
  await expect(page.getByRole('status').filter({ hasText: '1 records imported. They are marked as imported.' })).toBeVisible();

  // The same file again adds nothing (AC-IMX-5).
  await page.getByLabel('Water', { exact: true }).uncheck();
  await page.locator('input[type="file"][accept=".zip,.xml"]').setInputFiles(healthFile);
  await expect(preview).toContainText('Sleep: 0 new, 1 already recorded');
  await expect(preview.getByText('Nothing new to import.')).toBeVisible();
  await preview.getByRole('button', { name: 'Cancel' }).click();

  const body = sent.join('\n');
  expect(body).toContain('"sleep_main"');
  expect(body).not.toMatch(/HKQuantityType|HKCategoryType|<Record|"water"|BodyMass|StepCount/);
  expect(body.length, 'only normalised records are uploaded, never the file').toBeLessThan(xml.length / 20);

  // A Dosegram CSV file: the empty template is a header row, and a repeated row is a duplicate in the preview.
  const template = await page.request.get('/api/v1/import-templates/water');
  expect(template.status()).toBe(200);
  const header = (await template.text()).trim();
  const csv = `${header}\n${day}T09:00:00Z,300\n${day}T09:00:00Z,300\n`;
  await page
    .locator('input[type="file"][accept=".csv,text/csv"]')
    .setInputFiles({ name: 'water.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
  await expect(preview).toContainText('Water: 1 new, 1 already recorded');
  await preview.getByRole('button', { name: 'Import these records' }).click();
  await expect(page.getByRole('status').filter({ hasText: '1 records imported.' })).toBeVisible();

  // Each import is removed as a unit (AC-IMX-6), which leaves the shared account as it was.
  const history = page.getByRole('region', { name: 'Your imports' });
  for (let remaining = 2; remaining > 0; remaining--) {
    await expect(history.getByRole('button', { name: 'Remove' })).toHaveCount(remaining);
    await history.getByRole('button', { name: 'Remove' }).first().click();
    await expect
      .poll(async () => (await chrome(page)).popups.at(-1))
      .toBe('Remove this import? Every record it brought in is removed; your own records stay.');
    const removed = page.waitForResponse((r) => /\/api\/v1\/imports\/[^/]+$/.test(r.url()) && r.request().method() === 'DELETE');
    await answerPopup(page, 'remove');
    expect((await removed).status()).toBe(200);
  }
  await expect(history.getByRole('button', { name: 'Remove' })).toHaveCount(0);
}
