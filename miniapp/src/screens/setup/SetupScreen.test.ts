import { render, screen, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createHarness, type Harness } from '../../test/harness';
import TodayScreen from '../today/TodayScreen.svelte';
import SetupScreen from './SetupScreen.svelte';

function open(harness: Harness) {
  render(SetupScreen, { context: harness.context });
  return userEvent.setup();
}

const step = async (name: string) => (await screen.findByRole('heading', { name })).closest('li') as HTMLElement;

describe('Set-up', () => {
  it('shows four steps, each done when the records say so, and the count of the done ones', async () => {
    open(createHarness('empty'));

    expect(await step('What Dosegram records')).toBeTruthy();
    expect(within(await step('Your day')).getByText('To do')).toBeTruthy();
    expect(within(await step('What Dosegram records')).getByText('Done')).toBeTruthy();
    expect(screen.getByText(/^\d of 4 done$/)).toBeTruthy();
  });

  it('explains that consent is given in the chat and offers no consent control of its own', async () => {
    const harness = createHarness('empty');
    harness.api.tracking.withdraw('mood');
    const user = open(harness);

    const consent = await step('What Dosegram records');
    expect(within(consent).getByText(/only after you confirm it in the chat/)).toBeTruthy();
    expect(within(consent).queryByRole('checkbox')).toBeNull();
    expect(within(consent).queryByRole('switch')).toBeNull();
    await user.click(within(consent).getByRole('button', { name: 'Close and go to the chat' }));
    expect(harness.tg.closed).toBe(true);
  });

  it('leads to the day profile, the time zone and a first medication or water goal', async () => {
    const harness = createHarness('empty');
    const user = open(harness);

    await user.click(within(await step('Your day')).getByRole('button', { name: 'Set your day' }));
    expect(harness.router.route).toEqual({ screen: 'settings-day' });
    await user.click(within(await step('A first step')).getByRole('button', { name: 'Add a medication' }));
    expect(harness.router.route).toEqual({ screen: 'med-new' });
    await user.click(within(await step('A first step')).getByRole('button', { name: 'Set a water goal' }));
    expect(harness.router.route).toEqual({ screen: 'water-settings' });
    await user.click(within(await step('Time zone')).getByRole('button', { name: 'Change time zone' }));
    expect(harness.router.route).toEqual({ screen: 'settings-tz' });
  });

  it('counts the day as done once it is saved, and the first step once a goal or a medication exists', async () => {
    open(createHarness());

    expect(within(await step('Your day')).getByText('Done')).toBeTruthy();
    expect(within(await step('A first step')).getByText('Done')).toBeTruthy();
    expect(screen.getByText(/medications? (is|are) set up\./)).toBeTruthy();
  });
});

describe('The set-up card on Today', () => {
  it('appears while the day was never saved and leads to set-up', async () => {
    const harness = createHarness('empty');
    render(TodayScreen, { context: harness.context });
    const user = userEvent.setup();

    const card = await screen.findByRole('region', { name: 'Finish setting up' });
    await user.click(within(card).getByRole('link', { name: 'Set up' }));
    expect(harness.router.route).toEqual({ screen: 'setup' });
  });

  it('is not shown once the day is saved, nor on another day', async () => {
    const harness = createHarness();
    render(TodayScreen, { context: harness.context });

    await screen.findAllByText('Medication A');
    await harness.api.reminders();
    expect(screen.queryByRole('region', { name: 'Finish setting up' })).toBeNull();
  });
});
