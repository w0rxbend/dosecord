import { render, screen, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { Occurrence, OccurrenceAction } from '../../api/types';
import { createHarness } from '../../test/harness';
import OccurrenceSheet from './OccurrenceSheet.svelte';

const missed: Occurrence = {
  id: 'occ-missed',
  medicationName: 'Medication A',
  doseText: '1 tablet',
  scheduledTime: '04:00',
  state: 'missed',
  snoozeChoicesMinutes: [10, 30],
};

describe('Occurrence sheet', () => {
  it('corrects a missed dose and shows only the standing sentence beside the controls', async () => {
    const onAction = vi.fn<(occurrence: Occurrence, action: OccurrenceAction) => void>();
    const user = userEvent.setup();
    render(OccurrenceSheet, {
      props: { occurrence: missed, onAction, onClose: () => {} },
      context: createHarness().context,
    });

    const sheet = screen.getByRole('dialog', { name: 'Medication A' });
    expect(within(sheet).getAllByText('04:00')).toHaveLength(2);
    expect(sheet.textContent).toContain('Not confirmed');
    expect(sheet.textContent).toContain(
      "If you are unsure whether to take a late or missed dose, follow your clinician/pharmacist instructions.",
    );
    expect(within(sheet).queryByRole('textbox')).toBeNull();
    expect(within(sheet).queryByRole('button', { name: '30 min' })).toBeNull();

    await user.click(within(sheet).getByRole('button', { name: 'Change to taken' }));

    expect(onAction).toHaveBeenCalledWith(missed, { kind: 'taken' });
  });
});
