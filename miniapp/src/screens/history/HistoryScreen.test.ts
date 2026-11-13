import { render, screen, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createHarness, type Harness } from '../../test/harness';
import HistoryScreen from './HistoryScreen.svelte';

function open(harness: Harness) {
  render(HistoryScreen, { context: harness.context });
  return userEvent.setup();
}

const grid = () => screen.findByRole('group', { name: 'November 2026' });

describe('History calendar', () => {
  it('opens the first turned-on domain with one button per day, each named with the date and the value', async () => {
    open(createHarness());

    const month = await grid();
    expect(within(month).getAllByRole('button')).toHaveLength(30);
    expect(within(month).getByRole('button', { name: /^Thursday 12 November, \d of 4 taken$/ })).toBeTruthy();
    expect(within(month).getByRole('button', { name: 'Friday 13 November, still ahead' }).hasAttribute('disabled')).toBe(true);
    expect(screen.getByTitle('Monday').textContent).toBe('Mo');
  });

  it('switches the domain and keeps "no entry" apart from the ramp', async () => {
    const harness = createHarness();
    const user = open(harness);
    await grid();

    await user.click(screen.getByLabelText('Water'));

    const cell = await screen.findByRole('button', { name: 'Thursday 12 November, 1.2 of 2.0 l (60%)' });
    expect(cell.classList.contains('value')).toBe(true);
    const empty = within(await grid()).getAllByRole('button').filter((button) => button.getAttribute('aria-label')?.endsWith('no entry'));
    expect(empty.every((button) => button.classList.contains('empty'))).toBe(true);
    expect(harness.tg.haptics).toEqual(['selection']);
  });

  it('prints the values in the cells on request, so the calendar reads without colour', async () => {
    const user = open(createHarness());
    await screen.findByLabelText('Water');
    await user.click(screen.getByLabelText('Water'));
    await screen.findByRole('button', { name: 'Thursday 12 November, 1.2 of 2.0 l (60%)' });

    await user.click(screen.getByRole('switch', { name: 'Show values in cells' }));

    expect(screen.getByRole('button', { name: 'Thursday 12 November, 1.2 of 2.0 l (60%)' }).textContent).toContain('1.2');
  });

  it('moves between days with the arrow keys and between months with Page Up', async () => {
    const harness = createHarness();
    const user = open(harness);
    const month = await grid();
    const today = within(month).getByRole('button', { name: /^Thursday 12 November/ });
    expect(today.getAttribute('tabindex')).toBe('0');

    today.focus();
    await user.keyboard('{ArrowUp}');
    expect(document.activeElement?.getAttribute('aria-label')).toMatch(/^Thursday 5 November/);
    await user.keyboard('{PageUp}');

    expect(await screen.findByRole('group', { name: 'October 2026' })).toBeTruthy();
  });

  it('opens the day detail of a tapped day', async () => {
    const harness = createHarness();
    const user = open(harness);

    await user.click(within(await grid()).getByRole('button', { name: /^Tuesday 10 November/ }));

    expect(harness.router.route).toEqual({ screen: 'day', date: '2026-11-10' });
  });

  it('names the month buttons and offers no month after the current one', async () => {
    open(createHarness());
    await grid();

    expect(screen.getByRole('button', { name: 'Previous month' }).hasAttribute('disabled')).toBe(false);
    expect(screen.getByRole('button', { name: 'Next month' }).hasAttribute('disabled')).toBe(true);
  });
});
