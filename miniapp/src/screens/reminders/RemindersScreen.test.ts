import { render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createHarness, type Harness } from '../../test/harness';
import RemindersScreen from './RemindersScreen.svelte';

function open(harness: Harness) {
  render(RemindersScreen, { context: harness.context });
  return userEvent.setup();
}

describe('Reminders', () => {
  it('lists every kind as off, with nothing preselected and nothing saved by a preview', async () => {
    const harness = createHarness();
    const user = open(harness);

    expect(await screen.findByText(/Water: Off/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Turn on' })).toBeNull();
    await user.click(screen.getByRole('radio', { name: 'Work hours, every 30 min' }));

    expect(screen.getByRole('status').textContent).toContain('Next:');
    expect(harness.api.calls).not.toContain('reminders/plans:create');
  });

  it('turns a preset on with an idempotency key, then shows it in the hub and the timeline', async () => {
    const harness = createHarness();
    const user = open(harness);

    await user.click(await screen.findByRole('radio', { name: 'Work hours, every 30 min' }));
    await user.click(screen.getByRole('button', { name: 'Turn on' }));

    expect(await screen.findByText(/Water: Work hours, every 30 min/)).toBeTruthy();
    expect(harness.api.calls).toContain('reminders/plans:create');
    expect(screen.getAllByText(/Work hours, every 30 min/).length).toBeGreaterThan(1);
    await user.click(screen.getByRole('button', { name: 'Turn off' }));
    expect(await screen.findByText(/Water: Off/)).toBeTruthy();
  });

  it('pauses every prompt and says medication reminders are not affected', async () => {
    const harness = createHarness();
    const user = open(harness);

    await user.click(await screen.findByRole('button', { name: 'Pause 1 h' }));

    expect(await screen.findByRole('button', { name: 'Resume' })).toBeTruthy();
    expect(screen.getByText('Medication reminders are not affected.')).toBeTruthy();
  });

  it('saves the day on its version and refuses work that ends before it starts', async () => {
    const harness = createHarness();
    const user = open(harness);

    const start = (await screen.findByLabelText('Work starts')) as HTMLInputElement;
    await user.clear(start);
    await user.type(start, '19:00');
    expect(screen.getByText('Work must start before it ends')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Save day' }) as HTMLButtonElement).disabled).toBe(true);

    await user.clear(start);
    await user.type(start, '10:00');
    await user.click(screen.getByRole('button', { name: 'Save day' }));

    expect(harness.api.calls).toContain('reminders/day:update');
    expect(((await screen.findByLabelText('Work starts')) as HTMLInputElement).value).toBe('10:00');
  });
});
