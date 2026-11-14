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
    // The tile at the top repeats the headline, so the block is the scope.
    await within(await block('Medication')).findByText('360 scheduled doses');

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

    expect(await within(await block('Medication')).findByText('28 scheduled doses')).toBeTruthy();
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
  it('lists the patterns in the words of the server, each with how it was worked out', async () => {
    const user = open(createHarness());

    const pattern = await screen.findByText(/^On the 9 days you reached your water goal/);
    expect(pattern.textContent).toMatch(/It does not show a cause\.$/);
    await user.click(screen.getByText('How was this worked out?'));
    expect(screen.getByText('This is a count over your own records. It cannot show a cause.')).toBeTruthy();
    expect(screen.getByText(/^Bedtime and mood: 3 more days/)).toBeTruthy();
  });

  it('says where comparisons are turned on when they are off, and shows none', async () => {
    const harness = createHarness();
    harness.api.tracking.withdraw('mood');
    open(harness);

    expect(await screen.findByText(/^Comparisons between areas are off\./)).toBeTruthy();
    expect(screen.getByText(/Send \/insights in the chat/)).toBeTruthy();
    expect(screen.queryByText(/^On the 9 days/)).toBeNull();
  });

  it('shows the month so far with each medication on its own counts, and moves to the previous month', async () => {
    const harness = createHarness();
    const user = open(harness);

    expect(await screen.findByText('November 2026')).toBeTruthy();
    const rows = screen.getByRole('list', { name: 'By medication' });
    expect(within(rows).getAllByRole('listitem').length).toBeGreaterThan(0);
    expect(within(rows).getAllByText(/ of \d+ taken/).length).toBeGreaterThan(0);
    expect(screen.getByText(/^Water: \d+ more days with records for the month's figures/)).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Previous month' }));
    expect(await screen.findByText('October 2026')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Next month' })).toBeTruthy();
    expect(harness.api.calls.filter((call) => call === 'insights/month')).toHaveLength(2);
  });

  it('shows no month or pattern section when no domain is turned on', async () => {
    const harness = createHarness();
    harness.api.insightsOverview = async () => ({ rangeDays: 30, rangeLabel: '15 Oct to 13 Nov', ranges: [7, 30], blocks: [] });
    open(harness);

    expect(await screen.findByText('15 Oct to 13 Nov')).toBeTruthy();
    expect(screen.queryByText('Patterns in your records')).toBeNull();
    expect(screen.queryByText('By medication')).toBeNull();
  });

  it('shows a tile per domain with its figure and the previous period in words, and a tile leads to its block', async () => {
    const user = open(createHarness());

    const glance = await screen.findByRole('navigation', { name: 'At a glance' });
    const tiles = within(glance).getAllByRole('button');
    expect(tiles.map((tile) => tile.querySelector('.name')?.textContent)).toEqual(['Medication', 'Water', 'Mood', 'Sleep', 'Food']);
    expect(within(glance).getByText(/^Previous 30 days: /, { selector: '[data-domain="medication"] *' })).toBeTruthy();
    // The small lines are decorative: the words carry the figures.
    expect(within(glance).queryByRole('img')).toBeNull();

    await user.click(tiles[2]!);
    expect(document.activeElement?.id).toBe('insight-mood');
  });

  it('writes the comparison with the previous period inside each block, with no arrow', async () => {
    open(createHarness());

    const medication = await block('Medication');
    const comparison = within(medication).getByText(/^Previous 30 days: /);
    expect(comparison.textContent).not.toMatch(/[↑↓▲▼]/);
  });

  it('opens the day of a picked mark, by a second tap or by Enter', async () => {
    const harness = createHarness();
    harness.api.insightsOverview = async () => ({
      rangeDays: 7,
      rangeLabel: '6 Nov to 12 Nov',
      ranges: [7, 30, 90],
      blocks: [
        {
          id: 'hydration',
          title: 'Water',
          lines: ['average 1.2 l a day'],
          comparison: 'Previous 7 days: No data',
          charts: [
            {
              id: 'water-by-day',
              title: 'Water per day',
              summary: 'Water per day, 11 Nov to 12 Nov. Entries on 2 of 2 days.',
              scene: {
                width: 320,
                height: 116,
                nodes: [
                  { kind: 'hit', x: 0, y: 0, width: 160, height: 96, mark: 0 },
                  { kind: 'hit', x: 160, y: 0, width: 160, height: 96, mark: 1 },
                ],
              },
              table: { caption: 'Water per day', columns: ['Day', 'Water'], rows: [] },
              marks: ['Wed 11 Nov: 1.0 l', 'Thu 12 Nov: 1.4 l'],
              markDates: ['2026-11-11', '2026-11-12'],
            },
          ],
        },
      ],
    });
    const user = open(harness);

    const water = await block('Water');
    expect(within(water).getByRole('link', { name: 'Open this day' }).getAttribute('href')).toBe('/insights/history/20261112');
    const values = within(water).getByRole('slider', { name: 'Water per day: values' });
    await user.click(values.querySelector('.hit[data-mark="0"]')!);
    // The first tap shows the value only.
    expect(harness.router.route).toEqual({ screen: 'today' });
    await user.click(values.querySelector('.hit[data-mark="0"]')!);
    expect(harness.router.route).toEqual({ screen: 'day', date: '2026-11-11' });

    await harness.router.navigate('/insights');
    await user.click(screen.getByRole('slider', { name: 'Water per day: values' }));
    await user.keyboard('{End}{Enter}');
    expect(harness.router.route).toEqual({ screen: 'day', date: '2026-11-12' });
  });

  it('offers the first record of a domain that has none in the period, with its art hidden from screen readers', async () => {
    const harness = createHarness('empty');
    const user = open(harness);

    const medication = await block('Medication');
    expect(within(medication).queryByRole('img')).toBeNull();
    await user.click(within(medication).getByRole('link', { name: 'Open medications' }));
    expect(harness.router.route).toEqual({ screen: 'meds' });
  });
});
