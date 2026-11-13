import { render, screen, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createHarness, type Harness } from '../../test/harness';
import MedListScreen from './MedListScreen.svelte';

function open(harness: Harness) {
  render(MedListScreen, { context: harness.context });
  return userEvent.setup();
}

const names = () => screen.getAllByRole('listitem').map((item) => item.querySelector('.name')?.textContent);

describe('Medication list', () => {
  it('shows a labelled skeleton, then the active medications with the due one first', async () => {
    open(createHarness());

    expect(screen.getByRole('status', { name: 'Loading medications' })).toBeTruthy();
    await screen.findByText('Medication B');
    expect(names()).toEqual(['Medication A', 'Medication B', 'Medication C']);
  });

  it('shows the server lines for a row and a plain low-stock statement', async () => {
    open(createHarness());

    const row = (await screen.findByText('Medication B')).closest('li') as HTMLElement;
    expect(within(row).getByText('5 mg/ml liquid, 2 ml each time')).toBeTruthy();
    expect(within(row).getByText('Mon, Wed and Fri at 13:00. Next on Fri at 13:00')).toBeTruthy();
    expect(within(row).getByText('12 doses left')).toBeTruthy();
  });

  it('filters by status with counts, and archived rows are not links', async () => {
    const user = open(createHarness());
    await screen.findByText('Medication A');

    expect(screen.getByLabelText('Active 3')).toBeTruthy();
    await user.click(screen.getByLabelText('Archived 1'));

    expect(names()).toEqual(['Medication E']);
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.getByText('Archived medications keep their history and cannot be changed.')).toBeTruthy();
  });

  it('searches by name ignoring case and spaces, and announces the count', async () => {
    const user = open(createHarness());
    await screen.findByText('Medication A');

    await user.type(screen.getByLabelText('Search by name'), '  medication b ');

    expect(names()).toEqual(['Medication B']);
    expect(screen.getByRole('status').textContent).toBe('1 medication');
  });

  it('offers to clear a search that matches nothing', async () => {
    const user = open(createHarness());
    await screen.findByText('Medication A');
    await user.type(screen.getByLabelText('Search by name'), 'zzz');
    expect(screen.getByText('No medication matches.')).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Clear search' }));

    expect(names()).toHaveLength(3);
  });

  it('opens the editor from a row and from Add medication', async () => {
    const harness = createHarness();
    const user = open(harness);

    await user.click(await screen.findByRole('link', { name: /Medication A/ }));
    expect(harness.router.route).toEqual({ screen: 'med-edit', id: 'med-a' });

    await user.click(screen.getByRole('button', { name: 'Add medication' }));
    expect(harness.router.route).toEqual({ screen: 'med-new' });
  });

  it('invites the first medication when the registry is empty', async () => {
    open(createHarness('empty'));

    expect(await screen.findByText('No medications yet. Add the first one to get reminders.')).toBeTruthy();
  });

  it('shows the error state with a retry', async () => {
    const harness = createHarness('error');
    const user = open(harness);

    const alert = await screen.findByRole('alert');
    harness.api.scenario = 'typical';
    await user.click(within(alert).getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText('Medication A')).toBeTruthy();
  });

  it('renders a hostile name as text', async () => {
    const harness = createHarness();
    await harness.api.createMedication({
      ...(await harness.api.medication('med-a')),
      name: '<img src=x onerror=alert(1)> "&‮',
    });
    open(harness);

    expect(await screen.findByText(/<img src=x onerror=alert\(1\)>/)).toBeTruthy();
    expect(document.querySelector('img')).toBeNull();
  });
});
