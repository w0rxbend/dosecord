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

  it('offers the periods the server computes and no other', async () => {
    const harness = createHarness();
    const typical = harness.api.insightsOverview;
    harness.api.insightsOverview = async (rangeDays) => ({ ...(await typical(rangeDays)), ranges: [7, 30] });
    open(harness);
    await block('Medication');

    expect(screen.getAllByRole('radio').map((radio) => (radio as HTMLInputElement).value)).toEqual(['7', '30']);
  });

  it('shows a mood block in the words of the server, without a chart', async () => {
    const harness = createHarness();
    harness.api.insightsOverview = async () => ({
      rangeDays: 7,
      rangeLabel: '7 Nov to 13 Nov',
      ranges: [7, 30],
      blocks: [{ id: 'mood', title: 'Mood', lines: ['3 check-ins', '2 of 7 days have data'], charts: [] }],
    });
    open(harness);

    const mood = await block('Mood');
    expect(within(mood).getByText('3 check-ins')).toBeTruthy();
    expect(within(mood).getByText('2 of 7 days have data')).toBeTruthy();
    expect(within(mood).queryByRole('img')).toBeNull();
  });

  it('shows the notice of the server when no domain is turned on', async () => {
    const harness = createHarness();
    const notice = 'Statistics appear here for the records you turned on in the chat.';
    harness.api.insightsOverview = async () => ({ rangeDays: 30, rangeLabel: '15 Oct to 13 Nov', ranges: [7, 30], blocks: [], notice });
    open(harness);

    expect(await screen.findByText(notice)).toBeTruthy();
    expect(screen.queryByRole('region')).toBeNull();
  });

  it('shows the error state with a retry', async () => {
    const harness = createHarness('error');
    const user = open(harness);

    const alert = await screen.findByRole('alert');
    harness.api.scenario = 'typical';
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));

    expect(await block('Medication')).toBeTruthy();
  });

  it('shows the exact value of a picked day in a caption under the chart, by tap or by arrow key', async () => {
    const user = open(createHarness());

    const mood = await block('Mood');
    const values = within(mood).getByRole('slider', { name: 'Mood per day: values' });
    expect(within(mood).getByText(/^12 Nov: /)).toBeTruthy();

    values.focus();
    await user.keyboard('{ArrowLeft}');
    expect(values.getAttribute('aria-valuetext')).toMatch(/^11 Nov: /);
    expect(within(mood).getByText(values.getAttribute('aria-valuetext')!)).toBeTruthy();

    await user.click(values.querySelector('.hit[data-mark="0"]')!);
    expect(values.getAttribute('aria-valuetext')).toMatch(/^14 Oct: /);
  });

  it('leaves a domain whose consent is withdrawn out of the overview', async () => {
    const harness = createHarness();
    harness.api.tracking.withdraw('mood');
    open(harness);

    expect(await block('Water')).toBeTruthy();
    expect(screen.queryByRole('region', { name: 'Mood' })).toBeNull();
  });
});
