import { render, screen, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createHarness, type Harness } from '../../test/harness';
import InsightsScreen from './InsightsScreen.svelte';

function open(harness: Harness) {
  render(InsightsScreen, { context: harness.context });
  return userEvent.setup();
}

const block = (name: string) => screen.findByRole('region', { name });

describe('Insights', () => {
  it('shows a labelled skeleton, then one block per domain for the last 30 days', async () => {
    open(createHarness());

    expect(screen.getByRole('status', { name: 'Loading insights' })).toBeTruthy();
    const medication = await block('Medication');
    expect(within(medication).getByText('120 scheduled doses')).toBeTruthy();
    expect(await block('Water')).toBeTruthy();
    expect(screen.getByText('14 Oct to 12 Nov')).toBeTruthy();
  });

  it('gives every chart an accessible name equal to its visible summary', async () => {
    open(createHarness());

    const charts = within(await block('Medication')).getAllByRole('img');
    expect(charts).toHaveLength(2);
    for (const chart of charts) {
      const name = chart.getAttribute('aria-label') ?? '';
      expect(name.length).toBeGreaterThan(20);
      expect(screen.getByText(name)).toBeTruthy();
    }
  });

  it('keeps every chart within the scene node budget', async () => {
    const user = open(createHarness());
    await block('Medication');
    await user.click(screen.getByLabelText('90 days'));
    await screen.findByText('360 scheduled doses');

    for (const chart of screen.getAllByRole('img')) {
      expect(chart.querySelectorAll('*').length).toBeLessThanOrEqual(400);
    }
  });

  it('reveals the same data as a table', async () => {
    const user = open(createHarness());
    const medication = await block('Medication');

    const [toggle] = within(medication).getAllByRole('button', { name: 'Show as table' });
    await user.click(toggle as HTMLElement);

    const table = within(medication).getByRole('table', { name: 'Dose outcomes in this period' });
    expect(within(table).getAllByRole('row')).toHaveLength(6);
    expect(within(table).getByRole('rowheader', { name: 'not confirmed' })).toBeTruthy();
  });

  it('draws hydration as a vessel whose label states value, goal and percent', async () => {
    open(createHarness());

    const water = await block('Water');
    expect(within(water).getByRole('img', { name: 'Today: 1.2 L of 2.0 L, 60 percent of your goal.' })).toBeTruthy();
    expect(within(water).getByText(/^Water logged \d+ days in a row\.$/)).toBeTruthy();
  });

  it('changes the period for every block', async () => {
    const harness = createHarness();
    const user = open(harness);
    await block('Medication');

    await user.click(screen.getByLabelText('7 days'));

    expect(await screen.findByText('28 scheduled doses')).toBeTruthy();
    expect(screen.getByText('6 Nov to 12 Nov')).toBeTruthy();
    expect(harness.tg.haptics).toEqual(['selection']);
  });

  it('uses no judgement words, score or streak for medication records', async () => {
    open(createHarness());

    const text = (await block('Medication')).textContent ?? '';
    expect(text).not.toMatch(/streak|score|adherence|good|bad|poor|fail|%/i);
  });

  it('says there is no data instead of drawing an empty chart', async () => {
    open(createHarness('empty'));

    expect(within(await block('Medication')).getByText('No data in this period')).toBeTruthy();
    expect(screen.queryByRole('img')).toBeNull();
  });

  it('shows the error state with a retry', async () => {
    const harness = createHarness('error');
    const user = open(harness);

    const alert = await screen.findByRole('alert');
    harness.api.scenario = 'typical';
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));

    expect(await block('Medication')).toBeTruthy();
  });
});
