import { render, screen, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createHarness, type Harness } from '../../test/harness';
import TodayScreen from './TodayScreen.svelte';

function open(harness: Harness, date?: string) {
  render(TodayScreen, { props: { date }, context: harness.context });
  return userEvent.setup();
}

const section = (name: string) => screen.findByRole('region', { name });
const actionCalls = (harness: Harness) => harness.api.calls.filter((call) => call === 'occurrences/actions');

describe('Today screen', () => {
  it('shows a labelled skeleton while the day loads', () => {
    open(createHarness());

    const skeleton = screen.getByRole('status', { name: 'Loading today' });
    expect(skeleton.getAttribute('aria-busy')).toBe('true');
  });

  it('groups the day into due now, later and earlier', async () => {
    open(createHarness());

    const due = await section('Due now');
    expect(within(due).getByText('Medication A')).toBeTruthy();
    expect(within(due).getByText('1 tablet, 08:00, Due for 12 min')).toBeTruthy();
    expect(within(await section('Later')).getAllByRole('listitem')).toHaveLength(2);
    const earlier = await section('Earlier (2)');
    expect(within(earlier).getByText('Taken 07:58')).toBeTruthy();
    expect(within(earlier).getByText('Skipped')).toBeTruthy();
  });

  it('has one heading, the date under it and named icon controls', async () => {
    open(createHarness());

    await section('Due now');
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByText('Thursday 12 November')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Previous day' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Next day' })).toBeTruthy();
  });

  it('marks a due dose taken with one tap and one request, and offers undo', async () => {
    const harness = createHarness();
    const user = open(harness);

    await user.click(await screen.findByRole('button', { name: 'Taken' }));

    expect(await screen.findByText('Taken 08:12')).toBeTruthy();
    expect(screen.queryByRole('region', { name: 'Due now' })).toBeNull();
    expect(actionCalls(harness)).toHaveLength(1);
    expect(harness.toasts.current?.message).toBe('Marked taken at 08:12');
    expect(harness.toasts.current?.action?.label).toBe('Undo');
    expect(harness.tg.haptics).toEqual(['success']);
  });

  it('returns the dose to due now when the action is undone', async () => {
    const harness = createHarness();
    const user = open(harness);
    await user.click(await screen.findByRole('button', { name: 'Taken' }));
    await screen.findByText('Taken 08:12');

    harness.toasts.current?.action?.run();

    expect(within(await section('Due now')).getByText('Medication A')).toBeTruthy();
  });

  it('records a skip in neutral words', async () => {
    const harness = createHarness();
    const user = open(harness);

    await user.click(await screen.findByRole('button', { name: 'Skip' }));

    expect(await section('Earlier (3)')).toBeTruthy();
    expect(harness.toasts.current?.message).toBe('Marked skipped');
  });

  it('snoozes for one of the schedule choices', async () => {
    const harness = createHarness();
    const user = open(harness);

    await user.click(await screen.findByRole('button', { name: 'Snooze' }));
    await user.click(screen.getByRole('button', { name: '30 min' }));

    expect(within(await section('Later')).getByText('Snoozed until 08:42')).toBeTruthy();
    expect(harness.toasts.current?.message).toBe('Snoozed until 08:42');
  });

  it('shows the state from chat when the dose was already answered there', async () => {
    const harness = createHarness();
    const user = open(harness);
    const taken = await screen.findByRole('button', { name: 'Taken' });
    harness.api.resolveInChat('occ-3');

    await user.click(taken);

    expect(await screen.findByText('Taken 08:12')).toBeTruthy();
    expect(harness.toasts.current?.message).toBe('Already marked taken at 08:12 in chat');
    expect(harness.tg.haptics).toEqual([]);
  });

  it('rolls the card back and says so when the action is not saved', async () => {
    const harness = createHarness();
    const user = open(harness);
    const taken = await screen.findByRole('button', { name: 'Taken' });
    harness.api.scenario = 'error';

    await user.click(taken);

    expect(await screen.findByRole('button', { name: 'Taken' })).toBeTruthy();
    expect(harness.toasts.current?.message).toBe('Not saved. Try again.');
    expect(harness.tg.haptics).toEqual(['error']);
  });

  it('adds water with one tap and shows the new total', async () => {
    const harness = createHarness();
    const user = open(harness);
    expect(await screen.findByText('1.2 L of 2.0 L (60%)')).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Add 500 ml' }));

    expect(await screen.findByText('1.7 L of 2.0 L (85%)')).toBeTruthy();
    expect(harness.toasts.current?.message).toBe('Added 500 ml');
  });

  it('offers to add a medication when nothing is scheduled', async () => {
    const harness = createHarness('empty');
    const user = open(harness);

    expect(await screen.findByText('Nothing scheduled today.')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Add a medication' }));

    expect(harness.router.route).toEqual({ screen: 'med-new' });
  });

  it('shows the error with its reference and loads again on request', async () => {
    const harness = createHarness('error');
    const user = open(harness);

    const alert = await screen.findByRole('alert');
    expect(within(alert).getByText('Reference: MOCK-0001')).toBeTruthy();
    harness.api.scenario = 'typical';
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));

    expect(await section('Due now')).toBeTruthy();
  });

  it('lists a future day as a plan without action buttons', async () => {
    open(createHarness(), '2026-11-13');

    expect(within(await section('Later')).getAllByRole('listitem')).toHaveLength(5);
    expect(screen.getByText('Planned doses. You can answer them on the day.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Taken' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Back to today' })).toBeTruthy();
  });

  it('refreshes when the app becomes active again', async () => {
    const harness = createHarness();
    open(harness);
    await section('Due now');
    harness.api.resolveInChat('occ-3');

    harness.tg.activate();

    expect(await screen.findByText('Taken 08:12')).toBeTruthy();
  });
});
