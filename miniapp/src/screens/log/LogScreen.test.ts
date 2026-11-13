import { render, screen, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createHarness, type Harness } from '../../test/harness';
import LogScreen from './LogScreen.svelte';

function open(harness: Harness) {
  render(LogScreen, { context: harness.context });
  return userEvent.setup();
}

describe('Log hub', () => {
  it('lists the turned-on domains, each with one line of today in the server words', async () => {
    open(createHarness());

    const water = await screen.findByRole('link', { name: /^Water/ });
    expect(water.textContent).toContain('1.2 of 2.0 l (60%)');
    expect(within(water).getByRole('img', { name: 'Water: 1.2 of 2.0 litres, 60 percent' })).toBeTruthy();
    expect(screen.getByRole('link', { name: /^Mood/ }).textContent).toContain('Pleasant. Energy: medium.');
    expect(screen.getByRole('link', { name: /^Sleep/ }).textContent).toMatch(/Last: \d\d:\d\d to \d\d:\d\d, \d h \d\d min\./);
    expect(screen.queryByText('Not turned on')).toBeNull();
  });

  it('shows a domain without consent as not turned on, never as an empty tracker', async () => {
    const harness = createHarness();
    harness.api.tracking.withdraw('mood');
    open(harness);

    await screen.findByRole('link', { name: /^Water/ });
    expect(screen.queryByRole('link', { name: /^Mood/ })).toBeNull();
    const off = screen.getByRole('region', { name: 'Not turned on' });
    expect(within(off).getByText('Send /mood in the chat to turn it on.')).toBeTruthy();
  });

  it('with no domain turned on, says what can be tracked and leads to the consents', async () => {
    const harness = createHarness();
    harness.api.tracking.withdraw('hydration');
    harness.api.tracking.withdraw('mood');
    harness.api.tracking.withdraw('sleep');
    const user = open(harness);

    expect(await screen.findByText('Track water, mood or sleep. Each one is turned on separately, with your consent.')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Choose what to track' }));

    expect(harness.router.route).toEqual({ screen: 'settings-privacy' });
  });

  it('opens the sleep screen', async () => {
    const harness = createHarness();
    const user = open(harness);

    await user.click(await screen.findByRole('link', { name: /^Sleep/ }));

    expect(harness.router.route).toEqual({ screen: 'sleep' });
  });

  it('opens the water screen', async () => {
    const harness = createHarness();
    const user = open(harness);

    await user.click(await screen.findByRole('link', { name: /^Water/ }));

    expect(harness.router.route).toEqual({ screen: 'water' });
  });
});
