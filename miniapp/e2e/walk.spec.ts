// The paths of the go-live rehearsal that the flows leave out (docs/DEPLOY_MINIAPP.md section 7): a night, a meal with
// words, a card from the share builder and an export, each through the real API, and the controls the screenshot
// review found misdrawn. One launch: the session exchange
// allows six a minute per user (ARCHITECTURE API-18). The chat's half of a card and an export (the card arriving,
// the tap that confirms the export) needs a Telegram client and stays a live check.
import type { Page } from '@playwright/test';
import { expect, test } from './support/fixture';
import { routeTo } from './support/navigation';

const post = (path: RegExp) => (r: { url(): string; request(): { method(): string } }) =>
  path.test(new URL(r.url()).pathname) && r.request().method() === 'POST';

async function addLastNight(page: Page): Promise<void> {
  await expect(page.getByRole('heading', { level: 1, name: 'Sleep' })).toBeVisible();
  const form = page.getByRole('form', { name: 'Add a night or a nap' });
  // The form starts from last night; saving it as it stands is the shortest path.
  const saved = page.waitForResponse(post(/\/api\/v1\/sleep\/episodes$/));
  await form.getByRole('button', { name: 'Save night' }).click();
  expect((await saved).status()).toBe(201);
  const nights = page.getByRole('region', { name: 'Last 7 nights' });
  await expect(nights.getByRole('button', { name: /^Remove / })).toHaveCount(1);
}

async function addMealWithWords(page: Page): Promise<void> {
  await routeTo(page, '/log/food');
  await expect(page.getByRole('heading', { level: 1, name: 'Food' })).toBeVisible();
  await page.getByRole('button', { name: 'Add lunch' }).click();
  const form = page.getByRole('form', { name: 'Add a meal' });
  await form.getByLabel('What did you have? (optional)').fill('Lentil soup and bread');
  const saved = page.waitForResponse(post(/\/api\/v1\/meals$/));
  await form.getByRole('button', { name: 'Save meal' }).click();
  expect((await saved).status()).toBe(201);
  const lunch = page.getByRole('region', { name: 'Lunch' });
  await expect(lunch.getByText(/Lentil soup and bread/)).toBeVisible();
  // The words come back as "Recent" for the next lunch (MINI_APP 7.8 behaviour 2).
  await page.getByRole('button', { name: '+ Add a meal' }).click();
  await page.getByRole('form', { name: 'Add a meal' }).getByLabel('Lunch').check();
  await expect(page.getByRole('group', { name: 'Recent' }).getByRole('button', { name: 'Lentil soup and bread' })).toBeVisible();
  await page.getByRole('form', { name: 'Add a meal' }).getByRole('button', { name: 'Cancel' }).click();
}

async function sendACard(page: Page): Promise<void> {
  const previewed = page.waitForResponse((r) => /\/api\/v1\/report-cards\/[^/]+\/preview$/.test(r.url()));
  await routeTo(page, '/insights');
  expect((await previewed).status()).toBe(200);
  const builder = page.getByRole('region', { name: 'Send a card to your chat' });
  await expect(builder).toBeVisible();
  // The preview is the PNG the chat would receive, drawn by the server's resvg as on the host (the backend needs it on
  // the PATH or in DOSEGRAM_RESVG), shown under the policy's img-src, with its words beside it.
  const figure = builder.getByRole('figure');
  const image = figure.getByRole('img');
  await expect(image).toHaveAttribute('src', /^data:image\/png;base64,/);
  await expect.poll(() => image.evaluate((element: HTMLImageElement) => element.naturalWidth)).toBeGreaterThan(0);
  const alt = (await image.getAttribute('alt')) ?? '';
  expect(alt.length).toBeGreaterThan(0);
  await figure.getByText('Text of the card').click();
  await expect(figure.locator('.alt')).toHaveText(alt);
  const sent = page.waitForResponse(post(/\/api\/v1\/report-cards\/[^/]+\/deliveries$/));
  await builder.getByRole('button', { name: 'Send to my chat' }).click();
  expect((await sent).ok()).toBe(true);
  await expect(page.getByText('The card is on its way to your chat.')).toBeVisible();
}

async function requestAnExport(page: Page): Promise<void> {
  await routeTo(page, '/insights/reports');
  await expect(page.getByRole('heading', { level: 1, name: 'Reports and export' })).toBeVisible();
  const requested = page.waitForResponse(post(/\/api\/v1\/exports$/));
  await page.getByRole('button', { name: 'Request export' }).click();
  expect((await requested).ok()).toBe(true);
  await expect(page.getByText('Confirm it in the chat with Dosegram. This page updates by itself.')).toBeVisible();
  const files = page.getByRole('region', { name: 'Your files' });
  await expect(files.getByText('Full export')).toBeVisible();
  await expect(files.getByText('Waiting for your confirmation in chat')).toBeVisible();
}

/**
 * Found in the screenshot review of the rehearsal: a switch is drawn as a track with its thumb on every screen that
 * has one, and the day arrows of Food are strokes in the theme's colour, not black triangles.
 */
async function controlsAreDrawn(page: Page): Promise<void> {
  for (const path of ['/settings/notif', '/log/water/settings', '/insights/history']) {
    await routeTo(page, path);
    const toggle = page.getByRole('switch').first();
    await expect(toggle).toBeVisible();
    const box = await toggle.boundingBox();
    expect(box, path).not.toBeNull();
    expect({ path, width: box!.width, height: box!.height }).toEqual({ path, width: 48, height: 28 });
  }
  await routeTo(page, '/log/food');
  const arrow = page.getByRole('button', { name: 'Previous day' }).locator('svg');
  await expect(arrow).toBeVisible();
  const drawn = await arrow.evaluate((svg) => {
    const style = getComputedStyle(svg);
    return { fill: style.fill, stroke: style.stroke === getComputedStyle(svg.parentElement!).color };
  });
  expect(drawn).toEqual({ fill: 'none', stroke: true });
}

test('walk: a night, a meal with words, a card from the share builder, an export request', async ({ app }) => {
  test.setTimeout(120_000);
  const page = await app.launch({ startParam: 'sleep' });
  await addLastNight(page);
  await addMealWithWords(page);
  await sendACard(page);
  await requestAnExport(page);
  await controlsAreDrawn(page);
});
