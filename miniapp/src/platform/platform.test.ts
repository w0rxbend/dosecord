import { describe, expect, it, vi } from 'vitest';
import { bindAppearance } from './apply-theme';
import { FakeTelegramPort } from './fake';
import { createRealTelegramPort, parsePerformanceClass } from './real';

/** A stand-in for window.Telegram.WebApp that records what the adapter calls. */
function nativeApp() {
  const listeners = new Map<string, Set<() => void>>();
  const button = () => ({
    setText: vi.fn(),
    show: vi.fn(),
    hide: vi.fn(),
    enable: vi.fn(),
    disable: vi.fn(),
    showProgress: vi.fn(),
    hideProgress: vi.fn(),
    onClick: vi.fn(),
    offClick: vi.fn(),
  });
  return {
    initData: 'query_id=abc&signature=xyz',
    initDataUnsafe: { start_param: 'meds' },
    platform: 'android',
    colorScheme: 'dark' as const,
    themeParams: { bg_color: '#101010' },
    viewportStableHeight: 700,
    safeAreaInset: { top: 24, right: 0, bottom: 16, left: 0 },
    contentSafeAreaInset: { top: 40, right: 0, bottom: 0, left: 0 },
    MainButton: button(),
    BackButton: button(),
    HapticFeedback: { impactOccurred: vi.fn(), notificationOccurred: vi.fn(), selectionChanged: vi.fn() },
    isVersionAtLeast: (version: string) => version <= '8.0',
    onEvent: (event: string, callback: () => void) => {
      listeners.set(event, (listeners.get(event) ?? new Set()).add(callback));
    },
    offEvent: (event: string, callback: () => void) => listeners.get(event)?.delete(callback),
    ready: vi.fn(),
    expand: vi.fn(),
    disableVerticalSwipes: vi.fn(),
    enableClosingConfirmation: vi.fn(),
    disableClosingConfirmation: vi.fn(),
    showPopup: vi.fn((_params: unknown, callback: (id: string | null) => void) => callback('leave')),
    listenerCount: (event: string) => listeners.get(event)?.size ?? 0,
    emit: (event: string) => listeners.get(event)?.forEach((callback) => callback()),
  };
}

function adapt(app: ReturnType<typeof nativeApp>) {
  const host = { Telegram: { WebApp: app }, navigator: { userAgent: 'Telegram-Android/11.4 (Pixel; Android 14; SDK 34; LOW)' } };
  const port = createRealTelegramPort(host as unknown as Window);
  if (!port) throw new Error('adapter expected');
  return port;
}

describe('real Telegram adapter', () => {
  it('is absent when the Telegram script did not load', () => {
    expect(createRealTelegramPort({ navigator: { userAgent: '' } } as unknown as Window)).toBeNull();
  });

  it('exposes launch data, theme, insets and the performance class', () => {
    const port = adapt(nativeApp());

    expect(port.initData).toBe('query_id=abc&signature=xyz');
    expect(port.startParam).toBe('meds');
    expect(port.performanceClass).toBe('LOW');
    expect(port.theme()).toEqual({ scheme: 'dark', params: { bg_color: '#101010' } });
    expect(port.insets()).toEqual({
      safe: { top: 24, right: 0, bottom: 16, left: 0 },
      content: { top: 40, right: 0, bottom: 0, left: 0 },
      stableHeight: 700,
    });
  });

  it('drives the main button and deactivates it while progress shows', () => {
    const app = nativeApp();
    const port = adapt(app);

    port.mainButton.setText('Save medication');
    port.mainButton.setEnabled(false);
    port.mainButton.setProgress(true);
    port.mainButton.setProgress(false);

    expect(app.MainButton.setText).toHaveBeenCalledWith('Save medication');
    expect(app.MainButton.disable).toHaveBeenCalledOnce();
    expect(app.MainButton.showProgress).toHaveBeenCalledWith(false);
    expect(app.MainButton.hideProgress).toHaveBeenCalledOnce();
  });

  it('removes exactly the click handler it registered', () => {
    const app = nativeApp();
    const listener = () => undefined;

    const off = adapt(app).backButton.onClick(listener);
    off();

    expect(app.BackButton.onClick).toHaveBeenCalledWith(listener);
    expect(app.BackButton.offClick).toHaveBeenCalledWith(listener);
  });

  it('maps haptic events to the three native calls', () => {
    const app = nativeApp();
    const port = adapt(app);

    port.haptic('success');
    port.haptic('selection');
    port.haptic('impact-light');

    expect(app.HapticFeedback.notificationOccurred).toHaveBeenCalledWith('success');
    expect(app.HapticFeedback.selectionChanged).toHaveBeenCalledOnce();
    expect(app.HapticFeedback.impactOccurred).toHaveBeenCalledWith('light');
  });

  it('subscribes to every viewport event and unsubscribes from all of them', () => {
    const app = nativeApp();
    const off = adapt(app).onViewportChanged(() => undefined);
    expect(['viewportChanged', 'safeAreaChanged', 'contentSafeAreaChanged'].map(app.listenerCount)).toEqual([1, 1, 1]);

    off();

    expect(['viewportChanged', 'safeAreaChanged', 'contentSafeAreaChanged'].map(app.listenerCount)).toEqual([0, 0, 0]);
  });

  it('answers a popup with the pressed button id and marks destructive buttons', async () => {
    const app = nativeApp();

    const answer = await adapt(app).popup({
      message: 'Your changes are not saved.',
      buttons: [{ id: 'stay', text: 'Keep editing' }, { id: 'leave', text: 'Discard', kind: 'destructive' }],
    });

    expect(answer).toBe('leave');
    expect(app.showPopup.mock.calls[0]?.[0]).toMatchObject({
      buttons: [{ id: 'stay', type: 'default' }, { id: 'leave', type: 'destructive' }],
    });
  });

  it.each([
    ['Telegram-Android/11.4 (Pixel; Android 14; SDK 34; HIGH)', 'HIGH'],
    ['Mozilla/5.0 (iPhone)', 'UNKNOWN'],
  ])('reads the performance class from %j', (userAgent, expected) => {
    expect(parsePerformanceClass(userAgent)).toBe(expected);
  });
});

describe('bindAppearance', () => {
  it('applies tokens and insets at once and again on change, without a reload', () => {
    const tg = new FakeTelegramPort({ scheme: 'light', params: { text_color: '#102030' } });
    const root = document.createElement('div');

    const unbind = bindAppearance(tg, root);
    expect(root.dataset.scheme).toBe('light');
    expect(root.style.getPropertyValue('--dg-text')).toBe('#102030');
    expect(root.style.getPropertyValue('--dg-stable-height')).toBe('640px');

    tg.setTheme('dark');
    tg.setInsets({
      safe: { top: 24, right: 0, bottom: 16, left: 0 },
      content: { top: 40, right: 0, bottom: 0, left: 0 },
      stableHeight: 500,
    });
    expect(root.dataset.scheme).toBe('dark');
    expect(root.style.getPropertyValue('--dg-inset-top')).toBe('64px');
    expect(root.style.getPropertyValue('--dg-stable-height')).toBe('500px');

    unbind();
    tg.setTheme('light');
    expect(root.dataset.scheme).toBe('dark');
  });

  it('reduces motion on a low performance class', () => {
    const root = document.createElement('div');

    bindAppearance(new FakeTelegramPort({ performanceClass: 'LOW' }), root);

    expect(root.dataset.reducedMotion).toBe('true');
  });
});
