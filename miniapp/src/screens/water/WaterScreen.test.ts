import { render, screen, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { ApiError } from '../../api/client';
import { createHarness, type Harness } from '../../test/harness';
import WaterScreen from './WaterScreen.svelte';

function open(harness: Harness) {
  render(WaterScreen, { context: harness.context });
  return userEvent.setup();
}

const creates = (harness: Harness) => harness.api.calls.filter((call) => call === 'hydration/entries:create');
const todayList = () => screen.getByRole('region', { name: 'Today' });

describe('Water screen', () => {
  it('shows a labelled skeleton while the day loads', () => {
    open(createHarness());

    expect(screen.getByRole('status', { name: 'Loading water' }).getAttribute('aria-busy')).toBe('true');
  });

  it('draws the vessel against the own goal, with value, goal and percent in its name', async () => {
    open(createHarness());

    expect(await screen.findByRole('img', { name: 'Water: 1.2 of 2.0 litres, 60 percent' })).toBeTruthy();
    expect(screen.getByText('1.2 of 2.0 l (60%)')).toBeTruthy();
    expect(within(todayList()).getAllByRole('listitem')).toHaveLength(2);
  });

  it('adds a preset with one tap and one request, and offers undo', async () => {
    const harness = createHarness();
    const user = open(harness);

    await user.click(await screen.findByRole('button', { name: 'Add 250 ml' }));

    expect(await screen.findByText('1.45 of 2.0 l (72%)')).toBeTruthy();
    expect(creates(harness)).toHaveLength(1);
    expect(harness.tg.haptics).toEqual(['impact-light']);
    expect(harness.toasts.current?.message).toBe('Added 250 ml');
    expect(harness.toasts.current?.action?.label).toBe('Undo');
  });

  it('records two taps as two entries', async () => {
    const harness = createHarness();
    const user = open(harness);

    await user.click(await screen.findByRole('button', { name: 'Add 150 ml' }));
    await user.click(screen.getByRole('button', { name: 'Add 150 ml' }));

    expect(await screen.findByText('1.5 of 2.0 l (75%)')).toBeTruthy();
    expect(creates(harness)).toHaveLength(2);
  });

  it('reaching the goal gives one success haptic and no message of its own', async () => {
    const harness = createHarness();
    const user = open(harness);

    await user.click(await screen.findByRole('button', { name: 'Add 500 ml' }));
    await screen.findByText('1.7 of 2.0 l (85%)');
    await user.click(screen.getByRole('button', { name: 'Add 500 ml' }));

    expect(await screen.findByText('2.2 of 2.0 l (110%)')).toBeTruthy();
    expect(harness.tg.haptics).toEqual(['impact-light', 'success']);
    expect(harness.toasts.current?.message).toBe('Added 500 ml');
  });

  it('rolls the vessel back when the entry is not saved', async () => {
    const harness = createHarness();
    harness.api.addWater = () => Promise.reject(new ApiError('server', 'REF-1'));
    const user = open(harness);
    await screen.findByRole('img', { name: 'Water: 1.2 of 2.0 litres, 60 percent' });

    await user.click(screen.getByRole('button', { name: 'Add 500 ml' }));

    expect(harness.toasts.current?.message).toBe('Not saved. Try again.');
    expect(harness.tg.haptics).toEqual(['error']);
    expect(within(todayList()).getAllByRole('listitem')).toHaveLength(2);
  });

  it('removes an entry without a dialog and brings it back on undo', async () => {
    const harness = createHarness();
    const user = open(harness);

    await user.click(await screen.findByRole('button', { name: 'Remove 700 ml at 08:20' }));

    expect(within(todayList()).getAllByRole('listitem')).toHaveLength(1);
    expect(harness.tg.popups).toEqual([]);
    expect(harness.toasts.current?.message).toBe('Removed 700 ml');
    harness.toasts.current?.action?.run();
    expect(await screen.findByRole('button', { name: 'Remove 700 ml at 08:20' })).toBeTruthy();
    expect(harness.api.calls).not.toContain('hydration/entries/{id}:delete');
  });

  it('checks a typed amount before sending it', async () => {
    const harness = createHarness();
    const user = open(harness);

    await user.click(await screen.findByRole('button', { name: 'Other amount' }));
    await user.type(screen.getByLabelText('Amount in ml'), '6000');
    await user.click(screen.getByRole('button', { name: 'Add water' }));
    expect(screen.getByText('Enter a whole number from 1 to 5000')).toBeTruthy();
    expect(creates(harness)).toHaveLength(0);

    await user.clear(screen.getByLabelText('Amount in ml'));
    await user.type(screen.getByLabelText('Amount in ml'), '330');
    await user.click(screen.getByRole('button', { name: 'Add water' }));
    expect(await screen.findByText('1.53 of 2.0 l (76%)')).toBeTruthy();
  });

  it('draws the week as bars with a text summary and a table', async () => {
    const harness = createHarness();
    const user = open(harness);

    const week = await screen.findByRole('region', { name: 'Last 7 days' });
    expect(within(week).getByRole('img', { name: /^Water per day, 6 Nov to 12 Nov\./ })).toBeTruthy();
    await user.click(within(week).getByRole('button', { name: 'Show as table' }));
    expect(within(week).getAllByRole('row')).toHaveLength(8);
  });

  it('shows the consent-needed state when water tracking is off', async () => {
    const harness = createHarness();
    harness.api.tracking.withdraw('hydration');
    open(harness);

    expect(await screen.findByText('Water tracking is off. Dosegram records water only with your consent.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /^Add/ })).toBeNull();
  });
});
