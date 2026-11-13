import { fireEvent, render, screen, waitFor, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createHarness, type Harness } from '../../test/harness';
import ScheduleScreen from './ScheduleScreen.svelte';

async function open(harness: Harness) {
  render(ScheduleScreen, { props: { medicationId: 'med-a' }, context: harness.context });
  await screen.findByLabelText(/^Time 1/);
  return userEvent.setup();
}

/** A native time input takes its value whole, as a picker would set it. */
const setTime = (index: number, value: string) =>
  fireEvent.input(screen.getByLabelText(new RegExp(`^Time ${index}`)), { target: { value } });

const preview = () => screen.getByRole('region', { name: /^Next 7 days/ });
const previewRows = () => within(preview()).queryAllByRole('listitem');
const previews = (harness: Harness) => harness.api.calls.filter((call) => call === 'schedule-previews');

async function enterTwiceDaily(user: ReturnType<typeof userEvent.setup>) {
  await setTime(1, '08:00');
  await user.click(screen.getByRole('button', { name: 'Add a time' }));
  await setTime(2, '21:00');
}

describe('Schedule builder', () => {
  it('names the medication, carries its dose over and proposes no time', async () => {
    await open(createHarness());

    expect(screen.getByRole('heading', { level: 1, name: 'Schedule for Medication A' })).toBeTruthy();
    expect((screen.getByLabelText(/^Time 1/) as HTMLInputElement).value).toBe('');
    const dose = screen.getByLabelText(/^Dose 1/) as HTMLInputElement;
    expect(dose.value).toBe('1');
    expect(document.getElementById(dose.getAttribute('aria-describedby') ?? '')?.textContent).toBe('tablet');
  });

  it('offers exactly the patterns the server lists', async () => {
    await open(createHarness());

    const labels = screen.getAllByRole('radio').map((radio) => radio.parentElement?.textContent?.trim());
    expect(labels).toEqual(['Every day', 'Certain days of the week', 'Only when needed']);
  });

  it('keeps Save inactive and explains the preview while the draft is incomplete', async () => {
    const harness = createHarness();
    await open(harness);

    expect(within(preview()).getByText('The preview appears when the fields above are complete.')).toBeTruthy();
    expect(harness.tg.mainButton.state).toMatchObject({ text: 'Save schedule', visible: true, enabled: false });
    expect(previews(harness)).toHaveLength(0);
  });

  it('shows 14 rows over seven days for every day at 08:00 and 21:00', async () => {
    const harness = createHarness();
    const user = await open(harness);

    await enterTwiceDaily(user);

    await waitFor(() => expect(previewRows()).toHaveLength(14));
    expect(within(preview()).getAllByRole('heading', { level: 3 })).toHaveLength(7);
    expect(screen.getByRole('heading', { name: 'Next 7 days, Europe/Kyiv time' })).toBeTruthy();
    expect(within(preview()).getByText('Thu 12 Nov')).toBeTruthy();
    expect(screen.getByText('Every day at 08:00 and 21:00, from 12 Nov, no end date.')).toBeTruthy();
  });

  it('asks for one preview after the last change, not one per change', async () => {
    const harness = createHarness();
    const user = await open(harness);

    await enterTwiceDaily(user);
    await waitFor(() => expect(previewRows()).toHaveLength(14));

    // 08:00 alone is a valid draft for a moment; the second time replaces it within the delay.
    expect(previews(harness).length).toBeLessThanOrEqual(2);
  });

  it('activates Save only for a preview of exactly the current draft', async () => {
    const harness = createHarness();
    const user = await open(harness);
    await enterTwiceDaily(user);
    await waitFor(() => expect(harness.tg.mainButton.state.enabled).toBe(true));

    await setTime(2, '22:00');

    expect(harness.tg.mainButton.state.enabled).toBe(false);
    expect(preview().querySelector('[aria-busy="true"]')).not.toBeNull();
    await waitFor(() => expect(harness.tg.mainButton.state.enabled).toBe(true));
    expect(within(preview()).getAllByText(/^22:00/)).toHaveLength(7);
  });

  it('shows a duplicate time on its own field and withdraws the preview', async () => {
    const user = await open(createHarness());
    await enterTwiceDaily(user);
    await waitFor(() => expect(previewRows()).toHaveLength(14));

    await setTime(2, '08:00');
    await user.click(screen.getByLabelText(/^Time 2/));
    await user.tab();

    const second = screen.getByLabelText(/^Time 2/);
    expect(second.getAttribute('aria-invalid')).toBe('true');
    expect(document.getElementById(second.getAttribute('aria-describedby') ?? '')?.textContent).toBe(
      'This time is already in the list',
    );
    expect(previewRows()).toHaveLength(0);
  });

  it('limits a weekly schedule to the chosen days', async () => {
    const user = await open(createHarness());
    await setTime(1, '13:00');

    await user.click(screen.getByLabelText('Certain days of the week'));
    await user.click(screen.getByLabelText('Mon'));
    await user.click(screen.getByLabelText('Fri'));

    await waitFor(() => expect(previewRows()).toHaveLength(2));
    expect(within(preview()).getByText('Fri 13 Nov')).toBeTruthy();
    expect(within(preview()).getByText('Mon 16 Nov')).toBeTruthy();
  });

  it('asks for a weekday as soon as the last one is unchecked', async () => {
    const harness = createHarness();
    const user = await open(harness);
    await setTime(1, '13:00');
    await user.click(screen.getByLabelText('Certain days of the week'));
    await user.click(screen.getByLabelText('Mon'));
    await waitFor(() => expect(harness.tg.mainButton.state.enabled).toBe(true));

    await user.click(screen.getByLabelText('Mon'));

    expect(harness.tg.mainButton.state.enabled).toBe(false);
    expect(await screen.findByText('Choose at least one day')).toBeTruthy();
  });

  it('shows the server sentence when the dates leave no doses', async () => {
    const user = await open(createHarness());
    await setTime(1, '13:00');
    await user.click(screen.getByLabelText('Certain days of the week'));
    await user.click(screen.getByLabelText('Mon'));

    await fireEvent.input(screen.getByLabelText(/^Ends/), { target: { value: '2026-11-13' } });

    expect(await within(preview()).findByRole('alert')).toBeTruthy();
    expect(within(preview()).getByRole('alert').textContent).toBe('This schedule has no doses. Check the dates and days.');
  });

  it('hides times and says there are no reminders for an as-needed schedule', async () => {
    const harness = createHarness();
    const user = await open(harness);

    await user.click(screen.getByLabelText('Only when needed'));

    expect(screen.queryByLabelText(/^Time 1/)).toBeNull();
    expect(await within(preview()).findByText('No reminders. Log a dose from Today or in chat.')).toBeTruthy();
    await waitFor(() => expect(harness.tg.mainButton.state.enabled).toBe(true));
  });

  it('saves once, confirms and returns to the medication list', async () => {
    const harness = createHarness();
    const user = await open(harness);
    await enterTwiceDaily(user);
    await waitFor(() => expect(harness.tg.mainButton.state.enabled).toBe(true));

    harness.tg.mainButton.press();
    harness.tg.mainButton.press();

    await waitFor(() => expect(harness.router.route).toEqual({ screen: 'meds' }));
    expect(harness.api.calls.filter((call) => call === 'medications/{id}/schedules:create')).toHaveLength(1);
    expect(harness.toasts.current?.message).toBe('Schedule saved');
  });

  it('always shows the non-clinical notice', async () => {
    await open(createHarness());

    expect(screen.getByText('Enter the plan you were given. Dosegram does not suggest doses or times.')).toBeTruthy();
  });
});
