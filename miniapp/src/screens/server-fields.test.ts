import { fireEvent, render, screen, waitFor, within } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import type { ScheduleDraft } from '../api/types';
import { validateSchedule } from '../forms/schedule-rules';
import { createHarness, type Harness } from '../test/harness';
import MedEditorScreen from './editor/MedEditorScreen.svelte';
import ScheduleScreen from './schedule/ScheduleScreen.svelte';
import TodayScreen from './today/TodayScreen.svelte';

// The forms follow the option resources: a backend that keeps fewer fields than the mock gets
// fewer fields on screen, and nothing a person typed is silently dropped.

/** The mock with the options of a server that keeps a name, a dose and notes only. */
function withPlainMedications(harness: Harness): Harness {
  const full = harness.api.medicationOptions;
  harness.api.medicationOptions = async () => ({
    ...(await full()),
    forms: [],
    strengthUnits: [],
    colourTags: [],
    shapeTags: [],
    reminderNameToggle: false,
  });
  return harness;
}

function withOneDosePerSchedule(harness: Harness): { harness: Harness; created: ScheduleDraft[] } {
  const full = harness.api.scheduleOptions;
  const create = harness.api.createSchedule;
  const created: ScheduleDraft[] = [];
  harness.api.scheduleOptions = async () => ({ ...(await full()), perTimeDose: false });
  harness.api.createSchedule = (medicationId, draft) => {
    created.push(draft);
    return create(medicationId, draft);
  };
  return { harness, created };
}

describe('Forms on a server that keeps fewer fields', () => {
  it('shows no form, strength, look or reminder-name field in the medication editor', async () => {
    const harness = withPlainMedications(createHarness());
    render(MedEditorScreen, { props: {}, context: harness.context });
    await screen.findByLabelText(/^Name/);

    expect(screen.getByLabelText('Dose each time * required')).toBeTruthy();
    expect(screen.getByLabelText('Notes (optional)')).toBeTruthy();
    expect(screen.queryByLabelText('Form')).toBeNull();
    expect(screen.queryByLabelText(/^Strength/)).toBeNull();
    expect(screen.queryByRole('group', { name: 'Colour' })).toBeNull();
    expect(screen.queryByRole('switch')).toBeNull();
  });

  it('asks for a time only in the schedule builder and saves once the preview is in', async () => {
    const { harness, created } = withOneDosePerSchedule(createHarness());
    render(ScheduleScreen, { props: { medicationId: 'med-a' }, context: harness.context });
    await screen.findByLabelText(/^Time 1/);

    expect(screen.queryByLabelText(/^Dose 1/)).toBeNull();
    await fireEvent.input(screen.getByLabelText(/^Time 1/), { target: { value: '08:00' } });
    const preview = await screen.findByRole('region', { name: /^Next 7 days/ });
    await waitFor(() => expect(within(preview).queryAllByRole('listitem').length).toBeGreaterThan(0));
    await waitFor(() => expect(harness.tg.mainButton.state.enabled).toBe(true));

    harness.tg.mainButton.press();

    await waitFor(() => expect(created).toHaveLength(1));
    expect(created[0]?.rows.map((row) => row.time)).toEqual(['08:00']);
  });

  it('does not judge a row dose that the form does not ask for', () => {
    const form = {
      pattern: 'fixed_times' as const,
      rows: [{ key: 0, time: '08:00', dose: '' }],
      weekdays: [],
      startDate: '2026-11-13',
      endDate: '' as const,
    };

    expect(validateSchedule(form, false)).toEqual([]);
    expect(validateSchedule(form)).toEqual([{ field: 'rows.0.dose', code: 'dose_invalid' }]);
  });

  it('shows a day whose doses are answered in the chat without action buttons', async () => {
    const harness = createHarness();
    const full = harness.api.today;
    harness.api.today = async (date) => ({ ...(await full(date)), readOnly: true, answerInChat: true });
    render(TodayScreen, { props: {}, context: harness.context });

    const due = await screen.findByRole('region', { name: 'Due now' });

    expect(screen.getByText('Answer reminders in the chat. This screen shows what is recorded.')).toBeTruthy();
    expect(within(due).getByText('Medication A')).toBeTruthy();
    expect(within(due).queryByRole('button')).toBeNull();
    expect(screen.queryByText('Planned doses. You can answer them on the day.')).toBeNull();
  });
});
