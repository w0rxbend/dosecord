import { render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { ApiError } from '../../api/client';
import { parentPath, parsePath } from '../../app/routes';
import { createHarness, type Harness } from '../../test/harness';
import DeleteDataScreen from './DeleteDataScreen.svelte';
import SettingsScreen from './SettingsScreen.svelte';

function open(component: typeof DeleteDataScreen, harness: Harness) {
  render(component, { context: harness.context });
  return userEvent.setup();
}

// MA-SET-7, ROADMAP G2.17 and G5.7: "Delete account" and "Delete the data of one area" are danger buttons at the
// end of their page, each followed by a native confirmation and the confirmation in the chat.
describe('Delete data', () => {
  it('is reached from the Your data section of Settings, under Settings', async () => {
    const harness = createHarness();
    render(SettingsScreen, { context: harness.context });
    const user = userEvent.setup();

    await user.click(await screen.findByRole('link', { name: /^Delete data/ }));

    expect(harness.router.route).toEqual({ screen: 'settings-delete' });
    expect(parsePath('/settings/delete')).toEqual({ screen: 'settings-delete' });
    expect(parentPath({ screen: 'settings-delete' })).toBe('/settings');
  });

  it('sends the account deletion to the chat only after the native confirmation, and says so', async () => {
    const harness = createHarness();
    harness.tg.popupAnswer = 'delete';
    const user = open(DeleteDataScreen, harness);

    expect(screen.getByText(/Messages and images already in your Telegram chat stay there/)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Delete account' }));

    expect(harness.tg.popups.at(-1)?.buttons.map((button) => button.kind ?? 'default')).toEqual(['default', 'destructive']);
    expect(await screen.findByText(/Confirm in your chat with Dosegram/)).toBeTruthy();
    expect(harness.api.deletionRequests).toEqual(['account']);
  });

  it('sends nothing when the native confirmation is dismissed', async () => {
    const harness = createHarness();
    harness.tg.popupAnswer = 'keep';
    const user = open(DeleteDataScreen, harness);

    await user.click(screen.getByRole('button', { name: "Delete this area's data" }));

    expect(harness.tg.popups).toHaveLength(1);
    expect(harness.api.deletionRequests).toEqual([]);
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('sends the chosen area to the chat', async () => {
    const harness = createHarness();
    harness.tg.popupAnswer = 'delete';
    const user = open(DeleteDataScreen, harness);

    await user.click(screen.getByLabelText('Mood'));
    await user.click(screen.getByRole('button', { name: "Delete this area's data" }));

    expect(harness.tg.popups.at(-1)?.message).toBe('Delete all mood records? This cannot be undone.');
    expect(await screen.findByRole('status')).toBeTruthy();
    expect(harness.api.deletionRequests).toEqual(['mood']);
  });

  it('says when the request could not be sent', async () => {
    const harness = createHarness();
    harness.tg.popupAnswer = 'delete';
    harness.api.requestAccountDeletion = () => Promise.reject(new ApiError('server', 'REF-9'));
    const user = open(DeleteDataScreen, harness);

    await user.click(screen.getByRole('button', { name: 'Delete account' }));

    expect((await screen.findByRole('alert')).textContent).toBe('The request could not be sent. Try again.');
  });
});
