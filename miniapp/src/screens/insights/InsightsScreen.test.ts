import { render, screen, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import type { Chart, InsightsOverview } from '../../api/types';
import { createHarness, type Harness } from '../../test/harness';
import InsightsScreen from './InsightsScreen.svelte';

function open(harness: Harness) {
  render(InsightsScreen, { context: harness.context });
  return userEvent.setup();
}

const block = (name: string) => screen.findByRole('region', { name });

describe('Insights', () => {
  it('sends a card to the chat with one tap on the sentence it states', async () => {
    const harness = createHarness();
    const user = open(harness);

    const share = await block('Share a card');
    expect(within(share).getByText(/Anyone you send this to can keep it/)).toBeTruthy();
    await user.click(await within(share).findByRole('button', { name: 'Mood logged on 4 of 7 days' }));

    expect(harness.api.sharedCards).toEqual(['share-week-mood']);
    expect(harness.toasts.current?.message).toBe('The card is on its way to your chat.');
    expect(harness.tg.haptics).toContain('success');
  });

  it('asks first in discreet mode, and sends nothing when the person declines', async () => {
    const harness = createHarness();
    harness.api.discreet = true;
    const user = open(harness);
    const button = await within(await block('Share a card')).findByRole('button', { name: 'Mood logged on 4 of 7 days' });

    await user.click(button);
    expect(harness.tg.popups).toHaveLength(1);
    expect(harness.api.sharedCards).toEqual([]);

    harness.tg.popupAnswer = 'send';
    await user.click(button);
    expect(harness.api.sharedCards).toEqual(['share-week-mood']);
  });

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

/** Screen, section and panel padding around a chart on a 360 px phone. */
const CHART_WIDTH_AT_360 = 360 - 16 * 2 - 16 * 2 - 12 * 2;
/** "неприємно" at the 13 px caption minimum (Noto Sans). The gutter has to hold that word. */
const LONGEST_LEVEL_WORD_PX = 71;

const overviewCalls = (harness: Harness): number => harness.api.calls.filter((call) => call === 'insights/overview').length;

class NearViewport {
  static latest: NearViewport | undefined;
  readonly nodes: Element[] = [];

  constructor(
    readonly callback: IntersectionObserverCallback,
    readonly options?: IntersectionObserverInit,
  ) {
    NearViewport.latest = this;
  }

  observe(node: Element): void {
    this.nodes.push(node);
  }

  unobserve(node: Element): void {
    const index = this.nodes.indexOf(node);
    if (index >= 0) this.nodes.splice(index, 1);
  }

  disconnect(): void {
    this.nodes.splice(0);
  }

  takeRecords(): IntersectionObserverEntry[] {
    return [];
  }

  enter(): void {
    this.callback(
      this.nodes.map((target) => ({ isIntersecting: true, target }) as IntersectionObserverEntry),
      this as unknown as IntersectionObserver,
    );
  }
}

const originalObserver = globalThis.IntersectionObserver;

function installNearViewport(): void {
  NearViewport.latest = undefined;
  globalThis.IntersectionObserver = NearViewport as unknown as typeof IntersectionObserver;
}

function restoreNearViewport(): void {
  globalThis.IntersectionObserver = originalObserver;
}

function chartOverview(blockId: 'mood' | 'hydration', title: string, chart: Chart): InsightsOverview {
  return {
    rangeDays: 90,
    rangeLabel: '15 Aug to 12 Nov',
    ranges: [7, 30, 90],
    blocks: [{ id: blockId, title, lines: ['3 check-ins'], charts: [chart] }],
  };
}

describe('insight blocks below the first two (MA-INS-8)', () => {
  afterEach(() => restoreNearViewport());

  it('loads them within 200 px of the viewport and does not ask the overview again', async () => {
    installNearViewport();
    const harness = createHarness();
    open(harness);

    expect(await block('Medication')).toBeTruthy();
    expect(await block('Water')).toBeTruthy();
    expect(screen.queryByRole('region', { name: 'Mood' })).toBeNull();
    expect(screen.queryByRole('region', { name: 'Sleep' })).toBeNull();
    expect(screen.queryByRole('region', { name: 'Food' })).toBeNull();
    expect(document.querySelectorAll('[data-insight-lazy]')).toHaveLength(3);
    expect(NearViewport.latest?.options?.rootMargin).toBe('200px 0px');
    const reads = overviewCalls(harness);

    NearViewport.latest?.enter();

    expect(await block('Mood')).toBeTruthy();
    expect(await block('Sleep')).toBeTruthy();
    expect(await block('Food')).toBeTruthy();
    expect(document.querySelectorAll('[data-insight-lazy]')).toHaveLength(0);
    expect(overviewCalls(harness)).toBe(reads);
    expect(reads).toBe(1);
  });

  it('opens a lower block from its tile without another overview read', async () => {
    installNearViewport();
    const harness = createHarness();
    const user = open(harness);
    const glance = await screen.findByRole('navigation', { name: 'At a glance' });
    expect(screen.queryByRole('region', { name: 'Mood' })).toBeNull();
    const reads = overviewCalls(harness);

    await user.click(within(glance).getAllByRole('button')[2]!);

    expect(document.activeElement?.id).toBe('insight-mood');
    expect(overviewCalls(harness)).toBe(reads);
  });
});

describe('week chart points and the mood axis', () => {
  it('opens the day a week chart point names, on the tap', async () => {
    const harness = createHarness();
    harness.api.insightsOverview = async () =>
      chartOverview('hydration', 'Water', {
        id: 'water-by-week',
        title: 'Water per week',
        summary: 'Water per week, 15 Aug to 12 Nov. A week without an entry is a gap.',
        scene: {
          width: 320,
          height: 116,
          nodes: [
            { kind: 'hit', x: 44, y: 0, width: 138, height: 96, mark: 0 },
            { kind: 'hit', x: 182, y: 0, width: 138, height: 96, mark: 1 },
          ],
        },
        table: { caption: 'Water per week', columns: ['Week', 'Water'], rows: [] },
        marks: ['10 Aug to 16 Aug: 1.0 l', '17 Aug to 23 Aug: 1.4 l'],
        markDates: ['2026-08-16', '2026-08-23'],
      });
    const user = open(harness);

    const water = await block('Water');
    await user.click(within(water).getByRole('slider').querySelector('.hit[data-mark="0"]')!);

    expect(harness.router.route).toEqual({ screen: 'day', date: '2026-08-16' });
  });

  it('does not invent a day for a week chart point without one', async () => {
    const harness = createHarness();
    harness.api.insightsOverview = async () =>
      chartOverview('hydration', 'Water', {
        id: 'water-by-week',
        title: 'Water per week',
        summary: 'Water per week, 15 Aug to 12 Nov. A week without an entry is a gap.',
        scene: {
          width: 320,
          height: 116,
          nodes: [{ kind: 'hit', x: 44, y: 0, width: 276, height: 96, mark: 0 }],
        },
        table: { caption: 'Water per week', columns: ['Week', 'Water'], rows: [] },
        marks: ['10 Aug to 16 Aug: 1.0 l'],
      });
    const user = open(harness);

    await user.click((await block('Water')).querySelector('.hit[data-mark="0"]')!);

    expect(harness.router.route).toEqual({ screen: 'today' });
  });

  it('keeps the mood chart axis label readable at a 360 px viewport', async () => {
    document.documentElement.style.setProperty('--dg-text-caption', '13px');
    const harness = createHarness();
    harness.api.insightsOverview = async () =>
      chartOverview('mood', 'Mood', {
        id: 'mood-by-day',
        title: 'Mood per day',
        summary: 'Daily mood, 11 Nov to 12 Nov. Check-ins on 1 of 2 days. A day without a check-in is a gap.',
        scene: {
          width: 320,
          height: 116,
          nodes: [
            { kind: 'line', x1: 96, y1: 96, x2: 320, y2: 96, stroke: 'separator' },
            { kind: 'text', x: 0, y: 100, text: 'Дуже неприємно', fill: 'text-muted' },
            { kind: 'text', x: 96, y: 112, text: '11 Nov', fill: 'text-muted' },
            { kind: 'hit', x: 96, y: 0, width: 224, height: 96, mark: 0 },
          ],
        },
        table: { caption: 'Mood per day', columns: ['Day', 'Mood'], rows: [] },
        marks: ['12 Nov: 4.0'],
        markDates: ['2026-11-12'],
      });
    open(harness);

    const mood = await block('Mood');
    const label = within(mood).getByText('Дуже неприємно');
    expect(label.closest('svg')).toBeNull();
    expect(label.style.inlineSize).toBe('30%');
    expect(getComputedStyle(label).fontSize).toBe('13px');
    expect(getComputedStyle(label).overflowWrap).toBe('break-word');
    expect(getComputedStyle(label).whiteSpace).not.toBe('nowrap');
    expect((30 / 100) * CHART_WIDTH_AT_360).toBeGreaterThanOrEqual(LONGEST_LEVEL_WORD_PX);
    expect(within(mood).getByRole('img').textContent).toContain('11 Nov');
    expect(within(mood).getByRole('img').textContent).not.toContain('Дуже неприємно');
  });
});
