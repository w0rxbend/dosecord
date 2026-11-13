import { render, screen, waitFor, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createHarness, type Harness } from '../test/harness';
import App from './App.svelte';

function open(harness: Harness) {
  const { api, tg, router, toasts } = harness;
  render(App, { props: { context: { api, tg, router, toasts } } });
  return userEvent.setup();
}

const tabs = () => screen.getByRole('navigation', { name: 'Main sections' });
const heading = (name: string) => screen.findByRole('heading', { level: 1, name });

describe('Application shell', () => {
  it('opens Today with the tab bar and without the back button', async () => {
    const harness = createHarness();
    open(harness);

    expect(await heading('Today')).toBeTruthy();
    expect(within(tabs()).getAllByRole('link').map((link) => link.textContent?.trim())).toEqual(['Today', 'Meds', 'Log', 'Insights']);
    expect(within(tabs()).getByRole('link', { name: 'Today' }).getAttribute('aria-current')).toBe('page');
    expect(harness.tg.backButton.visible).toBe(false);
    expect(screen.getAllByRole('main')).toHaveLength(1);
  });

  it('changes tab, moves focus to the new heading and sets the document title', async () => {
    const harness = createHarness();
    const user = open(harness);
    await heading('Today');

    await user.click(within(tabs()).getByRole('link', { name: 'Meds' }));

    const title = await heading('Medications');
    await waitFor(() => expect(document.activeElement).toBe(title));
    expect(document.title).toBe('Medications | Dosegram');
    expect(within(tabs()).getByRole('link', { name: 'Meds' }).getAttribute('aria-current')).toBe('page');
    expect(harness.tg.haptics).toEqual(['impact-light']);
  });

  it('swaps the tab bar for the native buttons on a task screen and returns with Back', async () => {
    const harness = createHarness();
    open(harness);
    await harness.router.navigate('/meds');
    await heading('Medications');

    await harness.router.navigate('/meds/new');

    expect(await heading('New medication')).toBeTruthy();
    expect(screen.queryByRole('navigation')).toBeNull();
    await waitFor(() => expect(harness.tg.backButton.visible).toBe(true));
    expect(harness.tg.mainButton.state.visible).toBe(true);

    harness.tg.backButton.press();

    expect(await heading('Medications')).toBeTruthy();
    expect(harness.tg.mainButton.state.visible).toBe(false);
    expect(harness.tg.backButton.visible).toBe(false);
  });

  it('leads Back to the logical parent when a task screen was opened by a route', async () => {
    const harness = createHarness();
    harness.router.start('medit_med-a');
    open(harness);
    expect(await heading('Edit medication')).toBeTruthy();

    harness.tg.backButton.press();

    expect(await heading('Medications')).toBeTruthy();
  });

  it('opens Today with a notice for a link that matches no screen, without echoing it', async () => {
    const harness = createHarness();
    harness.router.start('water_<b>');
    open(harness);

    expect(await heading('Today')).toBeTruthy();
    const notice = screen.getByText(/That link did not match a screen\./);
    expect(notice.textContent).not.toContain('water');
  });
});
