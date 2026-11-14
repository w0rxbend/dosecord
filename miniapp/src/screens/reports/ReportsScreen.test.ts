import { render, screen } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { createHarness, type Harness } from '../../test/harness';
import ReportsScreen from './ReportsScreen.svelte';

function open(harness: Harness) {
  render(ReportsScreen, { context: harness.context });
  return userEvent.setup();
}

describe('Reports and export (S-14)', () => {
  it('asks for the confirmation in the chat; nothing is offered for download before it', async () => {
    const harness = createHarness();
    const user = open(harness);

    await user.click(await screen.findByRole('button', { name: 'Request export' }));

    expect((await screen.findByRole('status')).textContent).toBe(
      'Confirm it in the chat with Dosegram. This page updates by itself.',
    );
    expect(await screen.findByText('Waiting for your confirmation in chat')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Download' })).toBeNull();
    expect(harness.api.exportItems[0]?.kind).toBe('full_zip');
  });

  it('makes a record summary of the chosen period', async () => {
    const harness = createHarness();
    const user = open(harness);

    await user.click(await screen.findByLabelText('Last 12 months'));
    await user.click(screen.getByRole('button', { name: 'Make record summary' }));

    await screen.findByText('Waiting for your confirmation in chat');
    expect(harness.api.calls).toContain('exports:create');
    expect(harness.api.exportItems[0]?.kind).toBe('record_summary_pdf');
  });

  it('downloads a ready file with downloadFile and a ticket URL, never with the session', async () => {
    const harness = createHarness();
    const user = open(harness);
    await user.click(await screen.findByRole('button', { name: 'Request export' }));
    await screen.findByText('Waiting for your confirmation in chat');
    const id = harness.api.exportItems[0]!.id;
    harness.api.confirmExport(id);
    harness.tg.activate();

    await user.click(await screen.findByRole('button', { name: 'Download' }));

    expect(harness.tg.downloads).toEqual([
      { url: `http://localhost/api/v1/downloads/mock-${id}`, fileName: 'dosegram-export-20261114.zip' },
    ]);
    expect(harness.api.calls).toContain('exports:ticket');
  });

  it('names the time of the next export after the third one of 24 hours', async () => {
    const harness = createHarness();
    const user = open(harness);
    const request = await screen.findByRole('button', { name: 'Request export' });
    for (let i = 0; i < 3; i += 1) {
      await user.click(request);
      expect(await screen.findAllByText('Waiting for your confirmation in chat')).toHaveLength(i + 1);
    }

    await user.click(request);

    expect((await screen.findByRole('status')).textContent).toMatch(
      /^You can export 3 times in 24 hours\. The next export is possible at \d\d:\d\d\.$/,
    );
    expect(harness.api.exportItems).toHaveLength(3);
  });
});
