import { render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { createHarness, type Harness } from '../../test/harness';
import WaterSettingsScreen from './WaterSettingsScreen.svelte';

function open(harness: Harness) {
  render(WaterSettingsScreen, { context: harness.context });
  return userEvent.setup();
}

describe('Water goal and amounts', () => {
  it('says the goal is the person own and recommends no amount', async () => {
    const harness = createHarness();
    open(harness);

    expect(await screen.findByText('This is your own goal. Dosegram does not recommend an amount.')).toBeTruthy();
    expect((screen.getByLabelText(/Daily goal in ml/) as HTMLInputElement).value).toBe('2000');
    expect(harness.tg.mainButton.state).toMatchObject({ text: 'Save goal and amounts', visible: true, enabled: false });
  });

  it('saves a changed goal and amounts on the version it read', async () => {
    const harness = createHarness();
    const user = open(harness);
    const goal = await screen.findByLabelText(/Daily goal in ml/);

    await user.clear(goal);
    await user.type(goal, '2500');
    await user.type(screen.getByLabelText('Amount 4'), '750');
    harness.tg.mainButton.press();

    await vi.waitFor(async () => expect((await harness.api.waterSettings()).goalMl).toBe(2500));
    expect((await harness.api.waterSettings()).presetsMl).toEqual([150, 250, 500, 750]);
    expect(harness.toasts.current?.message).toBe('Goal and amounts saved');
  });

  it('shows what is wrong and sends nothing for an amount out of range', async () => {
    const harness = createHarness();
    const user = open(harness);

    await user.type(await screen.findByLabelText('Amount 4'), '9000');
    harness.tg.mainButton.press();

    expect(await screen.findByText('Enter whole numbers from 1 to 5000, each once')).toBeTruthy();
    expect(harness.api.calls).not.toContain('hydration/settings:update');
    expect(harness.tg.haptics).toEqual(['error']);
  });
});
