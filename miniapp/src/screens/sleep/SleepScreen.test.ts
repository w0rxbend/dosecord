import { render, screen, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createHarness, type Harness } from '../../test/harness';
import SleepScreen from './SleepScreen.svelte';

function open(harness: Harness) {
  render(SleepScreen, { context: harness.context });
  return userEvent.setup();
}

const now = () => screen.getByRole('region', { name: 'Now' });
const calls = (harness: Harness, name: string) => harness.api.calls.filter((call) => call === name);

describe('Sleep screen', () => {
  it('shows a labelled skeleton while sleep loads', () => {
    open(createHarness());

    expect(screen.getByRole('status', { name: 'Loading sleep' }).getAttribute('aria-busy')).toBe('true');
  });

  it('saves the night of the form with one request and states it with its source', async () => {
    const harness = createHarness('empty');
    const user = open(harness);
    expect(await within(await screen.findByRole('region', { name: 'Now' })).findByText('No night logged yet')).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Save night' }));

    expect(await within(now()).findByText('Last: 23:00 to 07:00, 8 h 00 min.')).toBeTruthy();
    expect(within(now()).getByText('From your check-ins')).toBeTruthy();
    expect(screen.getByText('Night of Thu 12 Nov: 23:00 to 07:00, 8 h 00 min')).toBeTruthy();
    expect(calls(harness, 'sleep/episodes:create')).toHaveLength(1);
    expect(harness.toasts.current?.message).toBe('Saved');
  });

  it('marks the field the server refused and stores nothing', async () => {
    const harness = createHarness('empty');
    const user = open(harness);
    const wakeDate = await screen.findByLabelText('Woke up, date');

    await user.clear(wakeDate);
    await user.type(wakeDate, '2026-11-11');
    await user.clear(screen.getByLabelText('Woke up, time'));
    await user.type(screen.getByLabelText('Woke up, time'), '22:00');
    await user.click(screen.getByRole('button', { name: 'Save night' }));

    expect(await screen.findByText('The wake time is before the sleep time')).toBeTruthy();
    expect(harness.api.tracking.sleep.episodesIn('2026-11-01', '2026-11-12')).toEqual([]);
  });

  it('"Going to sleep" is one tap and turns the button into "I\'m up"', async () => {
    const harness = createHarness('empty');
    const user = open(harness);

    await user.click(await screen.findByRole('button', { name: 'Going to sleep' }));

    expect(await within(now()).findByText('Going to sleep recorded at 08:12.')).toBeTruthy();
    expect(screen.getByRole('button', { name: "I'm up" })).toBeTruthy();
    expect(calls(harness, 'sleep/checkins:create')).toHaveLength(1);
  });

  it('"I\'m up" without an open night guesses nothing and asks for the night', async () => {
    const harness = createHarness('empty');
    harness.api.tracking.sleep.checkIn('going_to_sleep');
    const user = open(harness);
    harness.api.tracking.sleep.checkIn('awake');

    await user.click(await screen.findByRole('button', { name: "I'm up" }));

    expect(await screen.findByText('No "Going to sleep" was recorded. Add the night below.')).toBeTruthy();
  });

  it('draws the last seven nights with a text summary and the figures in the server words', async () => {
    const user = open(createHarness());

    const nights = await screen.findByRole('region', { name: 'Last 7 nights' });
    expect(within(nights).getByRole('img', { name: /^Sleep per night, 6 Nov to 12 Nov, from your check-ins\./ })).toBeTruthy();
    await user.click(within(nights).getByRole('button', { name: 'Show as table' }));
    expect(within(nights).getAllByRole('row')).toHaveLength(8);
    const figures = screen.getByRole('region', { name: 'Last 7 nights in figures' });
    expect(within(figures).getByText('your check-ins')).toBeTruthy();
  });

  it('removes a night', async () => {
    const harness = createHarness();
    const user = open(harness);

    const nights = await screen.findByRole('region', { name: 'Last 7 nights' });
    const buttons = within(nights).getAllByRole('button', { name: /^Remove Night of/ });
    await user.click(buttons[0]!);

    expect(calls(harness, 'sleep/episodes/{id}:delete')).toHaveLength(1);
    expect(await within(nights).findAllByRole('button', { name: /^Remove Night of/ })).toHaveLength(buttons.length - 1);
  });

  it('shows the consent-needed state when sleep tracking is off', async () => {
    const harness = createHarness();
    harness.api.tracking.withdraw('sleep');
    open(harness);

    expect(await screen.findByText('Sleep tracking is off. Dosegram records sleep only with your consent.')).toBeTruthy();
    expect(screen.getByText('Send /sleep in the chat to turn it on.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Going to sleep' })).toBeNull();
  });
});
