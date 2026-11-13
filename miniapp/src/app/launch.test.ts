import { screen, waitFor } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { ApiError, type ApiErrorCode } from '../api/client';
import { MockBackend } from '../mock/backend';
import { FakeTelegramPort } from '../platform/fake';
import { FIXED_NOW } from '../test/harness';
import { launch, type Environment } from './start';

/** A backend whose session exchange fails with the queued codes, then succeeds. */
function environment(failures: ApiErrorCode[] = []) {
  const tg = new FakeTelegramPort();
  const queue = [...failures];
  const state = { opened: 0, endSession: () => {} };
  const found: Environment = {
    tg,
    backend: (onSessionEnded) => {
      state.endSession = onSessionEnded;
      return {
        open: () => {
          state.opened += 1;
          const code = queue.shift();
          return code ? Promise.reject(new ApiError(code)) : Promise.resolve();
        },
        client: new MockBackend({ now: () => FIXED_NOW }),
      };
    },
  };
  return { tg, found, state };
}

function target(): HTMLElement {
  window.history.replaceState(null, '', '/');
  const host = document.createElement('div');
  document.body.append(host);
  return host;
}

const heading = (name: string) => screen.findByRole('heading', { level: 1, name });

afterEach(() => document.body.replaceChildren());

describe('Launch', () => {
  it('exchanges the session, then shows the application', async () => {
    const { tg, found, state } = environment();

    await launch(target(), found);

    expect(await heading('Today')).toBeTruthy();
    expect(state.opened).toBe(1);
    expect(tg.readyCalls).toBe(1);
    expect(tg.expanded).toBe(true);
  });

  it.each([
    ['account_required', 'Start Dosegram in chat first.'],
    ['unauthorised', 'This session has ended.'],
    ['client_too_old', 'This Telegram version is too old for Dosegram.'],
  ] as const)('shows a gate with one heading and Close when the exchange answers %s', async (code, title) => {
    const { tg, found } = environment([code]);
    const user = userEvent.setup();

    await launch(target(), found);

    expect(await heading(title)).toBeTruthy();
    expect(screen.getAllByRole('heading')).toHaveLength(1);
    expect(screen.queryByRole('navigation')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(screen.getAllByRole('button')).toHaveLength(1);
    expect(tg.closed).toBe(true);
  });

  it('offers Try again without a connection and opens the application when the exchange then succeeds', async () => {
    const { found, state } = environment(['network']);
    const user = userEvent.setup();

    await launch(target(), found);
    expect(await heading('No connection.')).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await heading('Today')).toBeTruthy();
    expect(state.opened).toBe(2);
    expect(screen.queryByRole('heading', { name: 'No connection.' })).toBeNull();
  });

  it('replaces the application with the gate when the session ends later', async () => {
    const { found, state } = environment();
    await launch(target(), found);
    await heading('Today');

    state.endSession();

    expect(await heading('This session has ended.')).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('navigation')).toBeNull());
  });
});
