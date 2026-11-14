import { render, screen, waitFor, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createHarness, type Harness } from '../../test/harness';
import FoodScreen from './FoodScreen.svelte';

function open(harness: Harness) {
  render(FoodScreen, { context: harness.context });
  return userEvent.setup();
}

const group = (name: string) => screen.getByRole('region', { name });
const calls = (harness: Harness, name: string) => harness.api.calls.filter((call) => call === name);

describe('Food screen (S-08)', () => {
  it('shows a labelled skeleton while the diary loads', () => {
    open(createHarness());

    expect(screen.getByRole('status', { name: 'Loading food' }).getAttribute('aria-busy')).toBe('true');
  });

  it('shows the four meal groups; a planned meal without an entry is "Not logged" with its Add action', async () => {
    open(createHarness('empty'));

    expect(await screen.findByRole('region', { name: 'Breakfast' })).toBeTruthy();
    for (const meal of ['Breakfast', 'Lunch', 'Dinner']) {
      expect(within(group(meal)).getByText('Not logged')).toBeTruthy();
      expect(within(group(meal)).getByRole('button', { name: `Add ${meal.toLowerCase()}` })).toBeTruthy();
    }
    expect(within(group('Snacks')).queryByText('Not logged')).toBeNull();
    expect(screen.getByText('Breakfast 0, Lunch 0, Dinner 0 of 7 days')).toBeTruthy();
  });

  it('logs a meal in two taps: "Add lunch", then "Save meal", with one request', async () => {
    const harness = createHarness('empty');
    const user = open(harness);

    await user.click(await screen.findByRole('button', { name: 'Add lunch' }));
    await user.type(screen.getByLabelText('What did you have? (optional)'), 'Soup and bread');
    await user.click(screen.getByRole('button', { name: 'Save meal' }));

    expect(await within(group('Lunch')).findByText('08:12 Soup and bread')).toBeTruthy();
    expect(within(group('Lunch')).queryByText('Not logged')).toBeNull();
    expect(calls(harness, 'meals:create')).toHaveLength(1);
    expect(harness.toasts.current?.message).toBe('Saved');
  });

  it('an edit replaces the meal, and Remove takes it away', async () => {
    const harness = createHarness('empty');
    const user = open(harness);
    await user.click(await screen.findByRole('button', { name: 'Add breakfast' }));
    await user.click(screen.getByRole('button', { name: 'Save meal' }));
    await user.click(await screen.findByRole('button', { name: 'Change Breakfast at 08:12' }));

    await user.click(screen.getByRole('radio', { name: 'Snack' }));
    await user.type(screen.getByLabelText('What did you have? (optional)'), 'Apple');
    await user.click(screen.getByRole('button', { name: 'Save meal' }));

    expect(await within(group('Snacks')).findByText('08:12 Apple')).toBeTruthy();
    expect(within(group('Breakfast')).getByText('Not logged')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Remove Snack at 08:12: Apple' }));
    expect(await within(group('Snacks')).findByRole('button', { name: 'Add snack' })).toBeTruthy();
    expect(harness.api.tracking.food.mealsIn('2026-11-12', '2026-11-12')).toEqual([]);
  });

  it('offers the descriptions saved before for the chosen meal type', async () => {
    const user = open(createHarness());

    await user.click(await screen.findByRole('button', { name: 'Add lunch' }));
    await user.click(await within(screen.getByRole('group', { name: 'Recent' })).findByRole('button', { name: 'Soup and bread' }));

    expect((screen.getByLabelText('What did you have? (optional)') as HTMLTextAreaElement).value).toBe('Soup and bread');
  });

  it('a previous day shows its meals and a way back to today', async () => {
    const user = open(createHarness());

    await user.click(await screen.findByRole('button', { name: 'Previous day' }));

    expect(await screen.findByRole('button', { name: 'Back to today' })).toBeTruthy();
    expect(screen.getAllByRole('button', { name: /^Remove / }).length).toBeGreaterThan(0);
  });

  it('with no planned meal a day is a count, and nothing says "Not logged"', async () => {
    const harness = createHarness('empty');
    const user = open(harness);
    for (const meal of ['Breakfast', 'Lunch', 'Dinner']) {
      await user.click(await screen.findByRole('checkbox', { name: meal }));
    }

    await user.click(screen.getByRole('button', { name: 'Save planned meals' }));

    await waitFor(() => expect(harness.toasts.current?.message).toBe('Planned meals saved'));
    expect(harness.api.tracking.food.settings().planned).toEqual([]);
    await waitFor(() => expect(screen.queryAllByText('Not logged')).toEqual([]));
  });

  it('shows no energy value, budget or target anywhere (MA-FOOD-1)', async () => {
    open(createHarness());

    await screen.findByRole('region', { name: 'Breakfast' });

    expect(document.body.textContent).not.toMatch(/kcal|calorie|energy|target|budget|remaining/i);
  });

  it('without the nutrition consent the diary is turned off and says how to turn it on', async () => {
    const harness = createHarness();
    harness.api.tracking.withdraw('nutrition');
    open(harness);

    expect(await screen.findByText('Meal tracking is off. Dosegram records meals only with your consent.')).toBeTruthy();
    expect(screen.getByText('Send /food in the chat to turn it on.')).toBeTruthy();
  });
});
