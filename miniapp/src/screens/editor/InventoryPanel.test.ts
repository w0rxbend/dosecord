import { render, screen, waitFor } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createHarness } from '../../test/harness';
import InventoryPanel from './InventoryPanel.svelte';

// ROADMAP G9.10: the count panel of the medication editor saves a first count without a version,
// then a refill, and shows the server's sentence; it never shows advice.

describe('Inventory panel', () => {
  it('saves a first count, shows the summary, then adds a refill', async () => {
    const harness = createHarness();
    render(InventoryPanel, { props: { medicationId: 'med-a', current: null }, context: harness.context });
    const user = userEvent.setup();

    expect(screen.queryByRole('button', { name: 'Add refill' })).toBeNull();
    await user.type(screen.getByLabelText(/^On hand/), '12');
    await user.click(screen.getByRole('button', { name: 'Save count' }));
    await waitFor(() => expect(screen.getByText('12 by your count.')).toBeTruthy());
    expect(harness.api.calls).toContain('medications/{id}/inventory:save');

    const refill = screen.getByLabelText(/^Refill: units added/);
    await user.clear(refill);
    await user.type(refill, '30');
    await user.click(screen.getByRole('button', { name: 'Add refill' }));
    await waitFor(() => expect(screen.getByText('42 by your count.')).toBeTruthy());
    expect(document.body.textContent?.toLowerCase()).not.toContain('should');
  });

  it('keeps Save disabled until the units each time are a number above zero', async () => {
    const harness = createHarness();
    render(InventoryPanel, { props: { medicationId: 'med-a', current: null }, context: harness.context });
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/^On hand/), '5');
    const perIntake = screen.getByLabelText(/^Units each time/);
    await user.clear(perIntake);
    await user.type(perIntake, '0');
    expect((screen.getByRole('button', { name: 'Save count' }) as HTMLButtonElement).disabled).toBe(true);
  });
});
