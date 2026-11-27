import { render, screen, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import type { ScheduleDraft } from '../../api/types';
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
    // The figures are the server's words for the day (MINI_APP 15.1).
    expect(await screen.findByText('1.2 of 2.0 l (60%)')).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Add 500 ml' }));

    expect(await screen.findByText('1.7 of 2.0 l (85%)')).toBeTruthy();
    expect(harness.toasts.current?.message).toBe('Added 500 ml');
    expect(harness.api.calls.filter((call) => call === 'hydration/entries:create')).toHaveLength(1);
  });

  it('takes a water entry back with the toast\'s Undo', async () => {
    const harness = createHarness();
    const user = open(harness);
    await screen.findByText('1.2 of 2.0 l (60%)');

    await user.click(screen.getByRole('button', { name: 'Add 500 ml' }));
    await screen.findByText('1.7 of 2.0 l (85%)');
    expect(harness.toasts.current?.action?.label).toBe('Undo');
    harness.toasts.current?.action?.run();

    expect(await screen.findByText('1.2 of 2.0 l (60%)')).toBeTruthy();
    expect(harness.api.calls.filter((call) => call === 'hydration/entries/{id}:delete')).toHaveLength(1);
  });

  it('logs a meal with one tap from Today, and Undo removes it', async () => {
    const harness = createHarness();
    const user = open(harness);
    const food = await section('Food');

    await user.click(within(food).getByRole('button', { name: 'Log lunch now' }));

    expect(harness.api.calls.filter((call) => call === 'meals:create')).toHaveLength(1);
    expect(harness.toasts.current?.message).toBe('Lunch logged');
    expect(harness.tg.haptics).toEqual(['impact-light']);
    harness.toasts.current?.action?.run();
    await screen.findByRole('button', { name: 'Log lunch now' });
    expect(harness.api.calls.filter((call) => call === 'meals/{id}:delete')).toHaveLength(1);
  });

  it('leaves food out when its consent is missing', async () => {
    const harness = createHarness();
    harness.api.tracking.withdraw('nutrition');
    open(harness);

    await screen.findByText('1.2 of 2.0 l (60%)');
    expect(screen.queryByRole('region', { name: 'Food' })).toBeNull();
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

  it('shows water and mood without the medication consent, and no dose', async () => {
    const harness = createHarness();
    harness.api.tracking.withdraw('medication');
    open(harness);

    expect(await screen.findByText('1.2 of 2.0 l (60%)')).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Mood' }).textContent).toContain('Pleasant. Energy: medium.');
    expect(screen.queryByRole('region', { name: 'Due now' })).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('shows where sleep stands, with its source', async () => {
    const harness = createHarness();
    open(harness);

    const sleep = await section('Sleep');
    expect(sleep.textContent).toMatch(/Last: \d\d:\d\d to \d\d:\d\d, \d h \d\d min\./);
    expect(within(sleep).getByText('From your records')).toBeTruthy();
  });

  it('leaves sleep out when its consent is missing', async () => {
    const harness = createHarness();
    harness.api.tracking.withdraw('sleep');
    open(harness);

    await screen.findByText('1.2 of 2.0 l (60%)');
    expect(screen.queryByRole('region', { name: 'Sleep' })).toBeNull();
  });

  it('carries a visible settings control', async () => {
    const harness = createHarness();
    const user = open(harness);

    await user.click(await screen.findByRole('button', { name: 'Settings' }));

    expect(harness.router.route).toEqual({ screen: 'settings' });
  });

  it('opens a dose row into a sheet of the scheduled time, state and recorded action', async () => {
    const user = open(createHarness());
    const earlier = await section('Earlier (2)');

    await user.click(within(earlier).getByRole('button', { name: /Medication C/ }));

    const sheet = await screen.findByRole('dialog', { name: 'Medication C' });
    expect(within(sheet).getByText('07:30')).toBeTruthy();
    expect(within(sheet).getByText('Taken 07:58')).toBeTruthy();
    const record = within(sheet).getByRole('list', { name: 'Record' });
    expect(within(record).getByText('07:58')).toBeTruthy();
    expect(within(record).getByText('Taken')).toBeTruthy();
    expect(within(sheet).queryByRole('textbox')).toBeNull();
    expect(within(sheet).queryByRole('spinbutton')).toBeNull();
  });

  it('changes a dose to skipped from the sheet with one request', async () => {
    const harness = createHarness();
    const user = open(harness);
    await user.click(within(await section('Due now')).getByRole('button', { name: /Medication A/ }));

    await user.click(within(await screen.findByRole('dialog', { name: 'Medication A' })).getByRole('button', { name: 'Change to skipped' }));

    expect(actionCalls(harness)).toHaveLength(1);
    expect(harness.toasts.current?.message).toBe('Marked skipped');
    const earlier = await section('Earlier (3)');
    expect(within(earlier).getByText('Medication A')).toBeTruthy();
    expect(within(earlier).getAllByText('Skipped')).toHaveLength(2);
  });

  it('takes the sheet answer back with undo', async () => {
    const harness = createHarness();
    const user = open(harness);
    await user.click(within(await section('Earlier (2)')).getByRole('button', { name: /Medication C/ }));

    await user.click(within(screen.getByRole('dialog', { name: 'Medication C' })).getByRole('button', { name: 'Undo' }));

    expect(actionCalls(harness)).toHaveLength(1);
    expect(within(await section('Due now')).getByText('Medication C')).toBeTruthy();
  });

  it('snoozes from the sheet for one offered length', async () => {
    const harness = createHarness();
    const user = open(harness);
    await user.click(within(await section('Due now')).getByRole('button', { name: /Medication A/ }));

    await user.click(within(screen.getByRole('dialog', { name: 'Medication A' })).getByRole('button', { name: '30 min' }));

    expect(actionCalls(harness)).toHaveLength(1);
    expect(within(await section('Later')).getByText('Snoozed until 08:42')).toBeTruthy();
  });

  it('shows a future dose sheet without corrections or a time field', async () => {
    const user = open(createHarness(), '2026-11-13');
    const later = await section('Later');
    const rows = within(later).getAllByRole('button', { name: /Medication A/ });

    await user.click(rows[0]!);

    const sheet = await screen.findByRole('dialog', { name: 'Medication A' });
    expect(within(sheet).getByText('08:00')).toBeTruthy();
    expect(within(sheet).queryByRole('button', { name: 'Change to taken' })).toBeNull();
    expect(within(sheet).queryByRole('button', { name: 'Undo' })).toBeNull();
    expect(within(sheet).queryByRole('textbox')).toBeNull();
  });

  it('lists an as-needed medication with no dose today and the limits the user set, and no reminder time', async () => {
    const harness = createHarness();
    await saveAsNeeded(harness, 'Medication F', { maxPerDay: 3, minGapMinutes: 240 }, '09:15');
    const user = open(harness);

    await user.click(await screen.findByRole('button', { name: 'As needed' }));

    const sheet = await screen.findByRole('dialog', { name: 'As needed' });
    const row = await within(sheet).findByRole('listitem', { name: /Medication F/ });
    expect(row.textContent).toContain('You set at most 3 a day.');
    expect(row.textContent).toContain('You set at least 240 min between logs.');
    expect(row.textContent).toContain('No reminders.');
    expect(row.textContent).not.toMatch(/\d{2}:\d{2}/);
    expect(within(sheet).queryByText('Medication C')).toBeNull();
    expect(within(sheet).queryByText('Medication A')).toBeNull();
  });

  it('does not state a limit an as-needed medication does not have', async () => {
    const harness = createHarness();
    await saveAsNeeded(harness, 'Medication G', {});
    const user = open(harness);

    await user.click(await screen.findByRole('button', { name: 'As needed' }));

    const row = await within(await screen.findByRole('dialog', { name: 'As needed' })).findByRole('listitem', { name: /Medication G/ });
    expect(row.textContent).toContain('No reminders.');
    expect(row.textContent).not.toMatch(/at most|between logs/);
  });

  it('records one mood number from Today and does not name what the number means', async () => {
    const harness = createHarness();
    const today = '2026-11-12';
    for (const checkin of await harness.api.moodCheckins(today, today)) await harness.api.removeCheckin(checkin.id);
    const user = open(harness);

    expect(await screen.findByRole('button', { name: 'Record 1' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Record 5' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: /pleasant|unpleasant|neutral/i })).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Record 4' }));

    expect(await screen.findByText('Recorded 4')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Record 4' })).toBeNull();
    expect(harness.api.calls.filter((call) => call === 'mood/checkins:create')).toHaveLength(1);
    expect(harness.router.route).toEqual({ screen: 'today' });
    const saved = await harness.api.moodCheckins(today, today);
    expect(saved.map((checkin) => checkin.valence)).toEqual([4]);
    expect(saved[0]?.energy).toBeUndefined();
  });
});

async function saveAsNeeded(
  harness: Harness,
  name: string,
  limits: Pick<ScheduleDraft, 'maxPerDay' | 'minGapMinutes'>,
  reminderTime?: string,
): Promise<void> {
  const medication = await harness.api.createMedication({
    name,
    form: 'tablet',
    strengthValue: null,
    strengthUnit: null,
    doseAmount: '1',
    doseUnit: 'tablet',
    colourTag: 'none',
    shapeTag: 'none',
    notes: '',
    showNameInReminders: true,
  });
  await harness.api.createSchedule(medication.id, {
    pattern: 'as_needed',
    rows: reminderTime === undefined ? [] : [{ time: reminderTime, dose: '1' }],
    weekdays: [],
    startDate: '2026-11-12',
    endDate: null,
    ...limits,
  });
}
