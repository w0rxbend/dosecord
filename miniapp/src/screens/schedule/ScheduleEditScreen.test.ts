import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import { createHarness, type Harness } from '../../test/harness';
import ScheduleEditScreen from './ScheduleEditScreen.svelte';

// Changing the times of an existing schedule (MINI_APP S-05, route sched_<id>): an edit is a new revision on the
// revision the screen loaded (ARCHITECTURE API-17), effective at once (ADR-020 decision 8).
async function open(harness: Harness, id = 'sch-a') {
  render(ScheduleEditScreen, { props: { id }, context: harness.context });
  await screen.findByLabelText(/^Time 1/);
}

const setTime = (index: number, value: string) =>
  fireEvent.input(screen.getByLabelText(new RegExp(`^Time ${index}`)), { target: { value } });
const timeValue = (index: number) => (screen.getByLabelText(new RegExp(`^Time ${index}`)) as HTMLInputElement).value;
const revisions = (harness: Harness) => harness.api.calls.filter((call) => call === 'schedules/{id}/revisions');

describe('Schedule edit', () => {
  it('opens with the times of the schedule and names the medication', async () => {
    await open(createHarness());

    expect(screen.getByRole('heading', { level: 1, name: 'Schedule for Medication A' })).toBeTruthy();
    expect([timeValue(1), timeValue(2)]).toEqual(['08:00', '21:00']);
  });

  it('keeps Save inactive while nothing changed', async () => {
    const harness = createHarness();
    await open(harness);

    expect(harness.tg.mainButton.state).toMatchObject({ text: 'Save schedule', visible: true, enabled: false });
  });

  it('saves a changed time as a revision of the loaded one and returns to the list', async () => {
    const harness = createHarness();
    await open(harness);

    await setTime(1, '08:30');
    await waitFor(() => expect(harness.tg.mainButton.state.enabled).toBe(true));
    harness.tg.mainButton.press();

    await waitFor(() => expect(revisions(harness)).toHaveLength(1));
    const saved = await harness.api.schedule('sch-a');
    expect(saved.rows.map((row) => row.time)).toEqual(['08:30', '21:00']);
    expect(saved.revision).toBe(2);
    await waitFor(() => expect(harness.router.route).toEqual({ screen: 'meds' }));
    expect(harness.toasts.current?.message).toBe('Schedule saved');
  });

  it('says that the schedule changed elsewhere when its revision is stale', async () => {
    const harness = createHarness();
    await open(harness);
    const schedule = await harness.api.schedule('sch-a');
    await harness.api.reviseSchedule('sch-a', { ...schedule, rows: [{ time: '07:00', dose: '1' }] }, schedule.revision);

    await setTime(1, '08:30');
    await waitFor(() => expect(harness.tg.mainButton.state.enabled).toBe(true));
    harness.tg.mainButton.press();

    expect(await screen.findByText('This item was changed elsewhere. Reload it, then make your change again.')).toBeTruthy();
    expect(harness.router.route).not.toEqual({ screen: 'meds' });
  });
});
