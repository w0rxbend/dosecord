import { render, screen, within } from '@testing-library/svelte';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../api/client';
import { locale, setLocale } from '../../i18n/index.svelte';
import { createHarness, type Harness } from '../../test/harness';
import PrivacyScreen from './PrivacyScreen.svelte';
import QuietHoursScreen from './QuietHoursScreen.svelte';
import SettingsScreen from './SettingsScreen.svelte';
import TimeZoneScreen from './TimeZoneScreen.svelte';
import DayProfileScreen from './DayProfileScreen.svelte';

function open(component: typeof SettingsScreen, harness: Harness) {
  render(component, { context: harness.context });
  return userEvent.setup();
}

describe('Settings', () => {
  it('saves a single choice on change and says "Saved" without moving focus', async () => {
    const harness = createHarness();
    const user = open(SettingsScreen, harness);

    await user.click(await screen.findByLabelText('Dark'));

    expect(await screen.findByText('Saved')).toBeTruthy();
    expect(harness.api.calls).toContain('settings:update');
    expect((await harness.api.settings()).imageTheme).toBe('dark');
  });

  it('switches the language through the settings API at once and stores it on the account (MA-SET-2)', async () => {
    const harness = createHarness();
    const user = open(SettingsScreen, harness);
    try {
      await user.selectOptions(await screen.findByRole('combobox', { name: 'Language' }), 'uk');

      expect(await screen.findByRole('heading', { name: 'Налаштування' })).toBeTruthy();
      expect(await screen.findByText('Збережено')).toBeTruthy();
      expect(locale()).toBe('uk');
      expect(harness.api.calls).toContain('settings:update');
      expect((await harness.api.settings()).language).toBe('uk');
      expect(document.documentElement.lang).toBe('uk');
    } finally {
      setLocale('en');
    }
  });

  it('brings the previous language back when saving the new one fails', async () => {
    const harness = createHarness();
    harness.api.saveSettings = () => Promise.reject(new ApiError('server', 'REF-3'));
    const user = open(SettingsScreen, harness);
    try {
      await user.selectOptions(await screen.findByRole('combobox', { name: 'Language' }), 'uk');

      expect(await screen.findByRole('alert')).toBeTruthy();
      expect(locale()).toBe('en');
      expect(screen.getByRole('heading', { name: 'Settings' })).toBeTruthy();
    } finally {
      setLocale('en');
    }
  });

  it('puts the previous value back with an inline error when the save fails', async () => {
    const harness = createHarness();
    harness.api.saveNotificationSettings = () => Promise.reject(new ApiError('server', 'REF-2'));
    const user = open(SettingsScreen, harness);

    const discreet = await screen.findByRole('switch', { name: /Discreet reminders/ });
    await user.click(discreet);

    expect(await screen.findByRole('alert')).toBeTruthy();
    expect((screen.getByRole('switch', { name: /Discreet reminders/ }) as HTMLInputElement).checked).toBe(false);
    expect(harness.tg.haptics).toEqual(['selection', 'error']);
  });

  it('shows the time zone, the quiet hours and the way to the consents as rows', async () => {
    const harness = createHarness();
    const user = open(SettingsScreen, harness);

    expect((await screen.findByRole('link', { name: /Time zone/ })).textContent).toContain('Europe/Kyiv');
    expect(screen.getByRole('link', { name: /Quiet hours/ }).textContent).toContain('Off');
    await user.click(screen.getByRole('link', { name: /Privacy and consent/ }));
    expect(harness.router.route).toEqual({ screen: 'settings-privacy' });
  });
});

describe('Time zone', () => {
  it('shows the preview before a zone can be saved, then saves it with MainButton', async () => {
    const harness = createHarness();
    const user = open(TimeZoneScreen, harness);

    expect(await screen.findByText('Dosegram uses Europe/Kyiv.')).toBeTruthy();
    expect(harness.tg.mainButton.state).toMatchObject({ text: 'Change time zone', enabled: false });
    await user.type(screen.getByRole('searchbox', { name: 'Search time zones' }), 'lisbon');
    await user.click(screen.getByRole('radio', { name: /Europe\/Lisbon/ }));

    expect(await screen.findByText('3 schedules follow your time zone and keep their clock times.')).toBeTruthy();
    expect(harness.tg.mainButton.state.enabled).toBe(true);
    harness.tg.mainButton.press();
    await vi.waitFor(async () => expect((await harness.api.settings()).timeZone).toBe('Europe/Lisbon'));
    expect(harness.tg.haptics.at(-1)).toBe('success');
  });
});

describe('Quiet hours', () => {
  it('accepts a range across midnight and refuses equal ends', async () => {
    const harness = createHarness();
    const user = open(QuietHoursScreen, harness);

    await user.click(await screen.findByRole('switch', { name: 'Use quiet hours' }));
    expect(harness.tg.mainButton.state.enabled).toBe(true);
    const end = screen.getByLabelText('End');
    await user.clear(end);
    await user.type(end, '22:30');
    expect(screen.getByText('Start and end are the same')).toBeTruthy();
    expect(harness.tg.mainButton.state.enabled).toBe(false);

    await user.clear(end);
    await user.type(end, '07:00');
    harness.tg.mainButton.press();
    await vi.waitFor(async () =>
      expect((await harness.api.notificationSettings()).quietHours).toEqual({ start: '22:30', end: '07:00' }),
    );
  });
});

describe('Privacy and consent', () => {
  it('lists each purpose with its state and how to change it, and offers no consent checkbox', async () => {
    const harness = createHarness();
    harness.api.tracking.withdraw('mood');
    open(PrivacyScreen, harness);

    const list = await screen.findByRole('list');
    const mood = within(list).getByText('Mood').closest('li')!;
    expect(within(mood).getByText('Off')).toBeTruthy();
    expect(within(mood).getByText('Off. Send /mood in the chat to turn it on.')).toBeTruthy();
    expect(within(list).getByText('Water').closest('li')?.textContent).toContain('On since');
    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(screen.getByText(/Bot chats are not end-to-end encrypted\./)).toBeTruthy();
  });
});

describe('Settings, complete', () => {
  it('reaches the day profile, set-up and the export from the settings page', async () => {
    const harness = createHarness();
    const user = open(SettingsScreen, harness);

    await user.click(await screen.findByRole('link', { name: /^Your day/ }));
    expect(harness.router.route).toEqual({ screen: 'settings-day' });
    await user.click(screen.getByRole('link', { name: /^Export everything/ }));
    expect(harness.router.route).toEqual({ screen: 'reports' });
    await user.click(screen.getByRole('link', { name: /^Set up Dosegram/ }));
    expect(harness.router.route).toEqual({ screen: 'setup' });
  });

  it('saves the day profile on the version it read and goes back', async () => {
    const harness = createHarness();
    await harness.router.navigate('/settings');
    await harness.router.navigate('/settings/day');
    const user = open(DayProfileScreen, harness);

    const wake = await screen.findByLabelText('Wake');
    await user.clear(wake);
    await user.type(wake, '06:30');
    await user.click(screen.getByRole('button', { name: 'Save day' }));

    await vi.waitFor(() => expect(harness.toasts.current?.message).toBe('Day saved'));
    expect(harness.api.calls).toContain('reminders/day:update');
    expect((await harness.api.reminders()).day.wake).toBe('06:30');
    await vi.waitFor(() => expect(harness.router.route).toEqual({ screen: 'settings' }));
  });
});
