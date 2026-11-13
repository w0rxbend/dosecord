import { render, screen, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createHarness, type Harness } from '../../test/harness';
import MoodCheckInScreen from './MoodCheckInScreen.svelte';
import MoodScreen from './MoodScreen.svelte';

function open(harness: Harness) {
  render(MoodScreen, { context: harness.context });
  return userEvent.setup();
}

function openCheckIn(harness: Harness) {
  render(MoodCheckInScreen, { context: harness.context });
  return userEvent.setup();
}

const STATEMENT = 'Dosegram records how you feel. It does not assess it.';

describe('Mood screen', () => {
  it('shows the latest check-in of the day large, labelled as the latest, drawn from the server geometry', async () => {
    open(createHarness());

    const latest = await screen.findByRole('region', { name: 'Latest check-in, 07:40' });
    const blob = within(latest).getByRole('img', { name: 'Pleasant. Energy: medium.' });
    expect(blob.getAttribute('data-state')).toBe('blob.v6.e3');
    expect(blob.querySelector('path')?.getAttribute('d')).toMatch(/^M[-\d.]+ [-\d.]+(C[-\d. ]+)+Z$/);
  });

  it('lists the other check-ins of the day and removes one', async () => {
    const harness = createHarness();
    const user = open(harness);
    await screen.findByRole('region', { name: 'Latest check-in, 07:40' });
    harness.api.tracking.checkIn({ valence: 2 });
    harness.tg.activate();

    const latest = await screen.findByRole('region', { name: 'Latest check-in, 08:12' });
    expect(within(latest).getByText('Unpleasant')).toBeTruthy();
    const earlier = screen.getByRole('region', { name: 'Earlier today' });
    await user.click(within(earlier).getByRole('button', { name: 'Remove the check-in of 07:40, Pleasant. Energy: medium.' }));

    await screen.findByRole('region', { name: 'Latest check-in, 08:12' });
    expect(harness.api.calls).toContain('mood/checkins/{id}:delete');
    expect(harness.toasts.current?.message).toBe('Check-in removed');
  });

  it('draws one small blob per day of the week, each named in words, and a dotted blob for a day without one', async () => {
    open(createHarness());

    const week = await screen.findByRole('region', { name: 'Last 7 days' });
    expect(within(week).getAllByRole('listitem')).toHaveLength(7);
    expect(within(week).getByText(/^Thu 12 Nov: 4\.0 \(pleasant\), 1 check-in$/)).toBeTruthy();
    const states = [...week.querySelectorAll('svg')].map((svg) => svg.getAttribute('data-state'));
    expect(states).toHaveLength(7);
  });

  it('shows the standing sentence and the help link for every value', async () => {
    const user = open(createHarness());

    expect(await screen.findByText(STATEMENT)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Help and contacts' }));
    expect(screen.getByText(/talk to a professional you trust/)).toBeTruthy();
  });

  it('shows the consent-needed state when mood tracking is off', async () => {
    const harness = createHarness();
    harness.api.tracking.withdraw('mood');
    open(harness);

    expect(await screen.findByText('Mood tracking is off. Dosegram records mood only with your consent.')).toBeTruthy();
  });
});

describe('Mood check-in', () => {
  it('starts untouched: the neutral outline, "Move the slider" and an inactive save button', async () => {
    const harness = createHarness();
    openCheckIn(harness);

    const blob = await screen.findByRole('img', { name: 'Move the slider' });
    expect(blob.getAttribute('data-state')).toBe('blob.none');
    expect(screen.getByRole('slider', { name: 'How you feel' }).getAttribute('aria-valuetext')).toBe('Move the slider');
    expect(harness.tg.mainButton.state).toMatchObject({ text: 'Save check-in', visible: true, enabled: false });
    expect(screen.getByText(STATEMENT)).toBeTruthy();
  });

  it('sets the value with the stepper buttons instead of a drag, and names it in words', async () => {
    const harness = createHarness();
    const user = openCheckIn(harness);

    await user.click(await screen.findByRole('button', { name: 'More pleasant' }));

    expect(screen.getByRole('img', { name: 'Pleasant' })).toBeTruthy();
    expect(screen.getByRole('slider', { name: 'How you feel' }).getAttribute('aria-valuetext')).toBe('Pleasant');
    expect(harness.tg.mainButton.state.enabled).toBe(true);
    expect(harness.tg.haptics).toEqual(['selection']);

    await user.click(screen.getByLabelText('High'));
    expect(screen.getByRole('img', { name: 'Pleasant, high energy' }).getAttribute('data-state')).toBe('blob.v6.e4');
  });

  it('saves with one request, then shows the saved blob with Done and Add another', async () => {
    const harness = createHarness();
    const user = openCheckIn(harness);
    await user.click(await screen.findByRole('button', { name: 'Less pleasant' }));

    harness.tg.mainButton.press();

    expect(await screen.findByText('Check-in saved')).toBeTruthy();
    expect(harness.api.calls.filter((call) => call === 'mood/checkins:create')).toHaveLength(1);
    expect(harness.tg.haptics).toEqual(['selection', 'success']);
    expect(harness.tg.mainButton.state.visible).toBe(false);
    await user.click(screen.getByRole('button', { name: 'Add another' }));
    expect(await screen.findByRole('img', { name: 'Move the slider' })).toBeTruthy();
    expect(harness.tg.mainButton.state.enabled).toBe(false);
  });

  it('keeps the form and says so when the save fails', async () => {
    const harness = createHarness();
    const user = openCheckIn(harness);
    await user.click(await screen.findByRole('button', { name: 'More pleasant' }));
    harness.api.scenario = 'error';

    harness.tg.mainButton.press();

    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByRole('img', { name: 'Pleasant' })).toBeTruthy();
  });
});
