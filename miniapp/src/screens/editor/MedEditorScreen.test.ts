import { render, screen, waitFor, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createHarness, type Harness } from '../../test/harness';
import MedEditorScreen from './MedEditorScreen.svelte';

async function open(harness: Harness, id?: string) {
  const view = render(MedEditorScreen, { props: { id }, context: harness.context });
  await screen.findByLabelText(/^Name/);
  return { user: userEvent.setup(), ...view };
}

const nameField = () => screen.getByLabelText(/^Name/) as HTMLInputElement;
const doseField = () => screen.getByLabelText(/^Dose each time/) as HTMLInputElement;
const created = (harness: Harness) => harness.api.calls.filter((call) => call === 'medications:create');

/** The text of the element that aria-describedby points at. */
function describedBy(control: HTMLElement): string {
  const ids = control.getAttribute('aria-describedby')?.split(' ') ?? [];
  return ids.map((id) => document.getElementById(id)?.textContent ?? '').join(' ');
}

describe('Medication editor validation', () => {
  it('labels every field and marks the required ones in the accessible name', async () => {
    await open(createHarness());

    expect(screen.getByLabelText('Name * required').getAttribute('aria-required')).toBe('true');
    expect(screen.getByLabelText('Dose each time * required')).toBeTruthy();
    expect(screen.getByLabelText('Form')).toBeTruthy();
    expect(screen.getByLabelText('Strength (optional)')).toBeTruthy();
    expect(screen.getByLabelText('Notes (optional)')).toBeTruthy();
    expect(nameField().getAttribute('autocomplete')).toBe('off');
  });

  it('shows no error while a field is untouched', async () => {
    await open(createHarness());

    expect(screen.queryByText('Enter a name')).toBeNull();
    expect(nameField().getAttribute('aria-invalid')).toBeNull();
  });

  it('validates a field when it loses focus and ties the error to it', async () => {
    const { user } = await open(createHarness());

    await user.click(nameField());
    await user.tab();

    expect(nameField().getAttribute('aria-invalid')).toBe('true');
    expect(describedBy(nameField())).toContain('Enter a name');
    expect(screen.getByRole('status').textContent).toBe('Enter a name');
  });

  it('clears the error as soon as the value is valid', async () => {
    const { user } = await open(createHarness());
    await user.click(nameField());
    await user.tab();

    await user.type(nameField(), 'M');

    expect(screen.queryByText('Enter a name')).toBeNull();
    expect(nameField().getAttribute('aria-invalid')).toBeNull();
  });

  it('rejects a name longer than the limit from the server options', async () => {
    const { user } = await open(createHarness());

    await user.click(nameField());
    await user.paste('x'.repeat(81));
    await user.tab();

    expect(describedBy(nameField())).toContain('Use at most 80 characters');
  });

  it.each([
    ['0', 'Enter a number greater than 0'],
    ['abc', 'Enter a number greater than 0'],
    ['1.2345', 'Enter a number greater than 0'],
  ])('rejects the dose %j', async (text, message) => {
    const { user } = await open(createHarness());

    await user.clear(doseField());
    await user.type(doseField(), text);
    await user.tab();

    expect(describedBy(doseField())).toContain(message);
  });

  it('asks for the dose when it is empty', async () => {
    const { user } = await open(createHarness());

    await user.clear(doseField());
    await user.tab();

    expect(describedBy(doseField())).toContain('Enter how much you take each time');
  });

  it('asks for a unit once a strength is entered', async () => {
    const { user } = await open(createHarness());

    await user.type(screen.getByLabelText('Strength (optional)'), '500');
    await user.click(screen.getByLabelText('Strength unit'));
    await user.tab();

    expect(describedBy(screen.getByLabelText('Strength unit'))).toContain('Choose a unit for the strength');
  });

  it('moves the dose by a quarter with the stepper buttons', async () => {
    const { user } = await open(createHarness());

    await user.click(screen.getByRole('button', { name: 'More' }));
    await user.click(screen.getByRole('button', { name: 'More' }));
    await user.click(screen.getByRole('button', { name: 'Less' }));

    expect(doseField().value).toBe('1.25');
  });
});

describe('Medication editor saving', () => {
  it('enables the bottom button only once the form has changed', async () => {
    const harness = createHarness();
    const { user } = await open(harness);

    expect(harness.tg.mainButton.state).toMatchObject({ text: 'Save medication', visible: true, enabled: false });
    await user.type(nameField(), 'Medication F');

    expect(harness.tg.mainButton.state.enabled).toBe(true);
  });

  it('summarises the errors, focuses the first invalid field and sends nothing', async () => {
    const harness = createHarness();
    const { user } = await open(harness);
    await user.clear(doseField());

    harness.tg.mainButton.press();

    const summary = await screen.findByRole('alert');
    expect(within(summary).getByRole('heading', { name: 'Check 2 fields' })).toBeTruthy();
    expect(within(summary).getByRole('button', { name: 'Name: Enter a name' })).toBeTruthy();
    expect(document.activeElement).toBe(nameField());
    expect(harness.tg.haptics).toEqual(['error']);
    expect(created(harness)).toHaveLength(0);
  });

  it('leads from a summary entry to its field', async () => {
    const harness = createHarness();
    const { user } = await open(harness);
    await user.clear(doseField());
    harness.tg.mainButton.press();

    await user.click(await screen.findByRole('button', { name: /^Dose each time:/ }));

    expect(document.activeElement).toBe(doseField());
  });

  it('accepts a comma as the decimal separator and stores a canonical decimal', async () => {
    const harness = createHarness();
    const { user } = await open(harness);
    await user.type(nameField(), 'Medication F');
    await user.clear(doseField());
    await user.type(doseField(), '1,50');

    harness.tg.mainButton.press();

    await waitFor(() => expect(harness.toasts.current?.message).toBe('Medication saved'));
    expect((await harness.api.medication('med-new-1')).doseAmount).toBe('1.5');
  });

  it('saves once, confirms and continues to the schedule of the new medication', async () => {
    const harness = createHarness();
    const { user } = await open(harness);
    await user.type(nameField(), '  Medication F ');

    harness.tg.mainButton.press();
    harness.tg.mainButton.press();

    await waitFor(() => expect(harness.router.route).toEqual({ screen: 'schedule-new', medicationId: 'med-new-1' }));
    expect(created(harness)).toHaveLength(1);
    expect((await harness.api.medication('med-new-1')).name).toBe('Medication F');
    expect(harness.tg.haptics).toEqual(['success']);
  });

  it('shows a server field error in the same place as a client one', async () => {
    const harness = createHarness();
    const { user } = await open(harness);
    await user.type(nameField(), 'medication a');

    harness.tg.mainButton.press();

    await waitFor(() => expect(describedBy(nameField())).toContain('You already have a medication with this name.'));
    expect(document.activeElement).toBe(nameField());
    expect(harness.router.route).toEqual({ screen: 'today' });
  });

  it('shows a failed save in the page and keeps what was typed', async () => {
    const harness = createHarness();
    const { user } = await open(harness);
    await user.type(nameField(), 'Medication F');
    harness.api.scenario = 'error';

    harness.tg.mainButton.press();

    expect(await screen.findByText('Reference: MOCK-0001')).toBeTruthy();
    expect(nameField().value).toBe('Medication F');
    expect(harness.tg.mainButton.state).toMatchObject({ enabled: true, inProgress: false });
  });

  it('loads an existing medication, explains a dose change and saves against its version', async () => {
    const harness = createHarness();
    const { user } = await open(harness, 'med-a');
    expect(nameField().value).toBe('Medication A');

    await user.clear(doseField());
    await user.type(doseField(), '2');
    expect(screen.getByText(/The new dose applies to doses from now on/)).toBeTruthy();
    harness.tg.mainButton.press();

    await waitFor(() => expect(harness.router.route).toEqual({ screen: 'meds' }));
    expect(await harness.api.medication('med-a')).toMatchObject({ doseAmount: '2', version: 2 });
  });

  it('links an existing medication to its schedule, and one without a schedule to a new one', async () => {
    const harness = createHarness();
    const { user, unmount } = await open(harness, 'med-a');

    await user.click(await screen.findByRole('link', { name: 'Change times' }));
    expect(harness.router.route).toEqual({ screen: 'schedule-edit', id: 'sch-a' });

    unmount();
    const created = await harness.api.createMedication({ ...(await harness.api.medication('med-a')), name: 'New one' });
    const again = await open(harness, created.id);
    await again.user.click(await screen.findByRole('link', { name: 'Add times' }));
    expect(harness.router.route).toEqual({ screen: 'schedule-new', medicationId: created.id });
  });

  it('offers no schedule link while a new medication is entered', async () => {
    await open(createHarness());

    expect(screen.queryByRole('link', { name: 'Change times' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Add times' })).toBeNull();
  });

  it('always shows the non-clinical notice', async () => {
    await open(createHarness());

    expect(screen.getByText('These are your own records. Dosegram does not check doses or interactions.')).toBeTruthy();
  });
});

describe('Medication editor leaving', () => {
  it('asks with the native popup before a changed form is left, and stays on "Keep editing"', async () => {
    const harness = createHarness();
    const { user } = await open(harness);
    await harness.router.navigate('/meds/new');
    await user.type(nameField(), 'Medication F');
    harness.tg.popupAnswer = 'stay';

    await harness.router.back();

    expect(harness.tg.popups).toHaveLength(1);
    expect(harness.tg.popups[0]?.buttons.map((button) => button.text)).toEqual(['Keep editing', 'Discard']);
    expect(harness.router.route).toEqual({ screen: 'med-new' });
    expect(harness.tg.closingConfirmation).toBe(true);
  });

  it('removes its bottom-button handler when the screen leaves', async () => {
    const harness = createHarness();
    const { unmount } = await open(harness);
    expect(harness.tg.mainButton.handlerCount).toBe(1);

    unmount();

    expect(harness.tg.mainButton.handlerCount).toBe(0);
    expect(harness.tg.mainButton.state.visible).toBe(false);
  });
});
