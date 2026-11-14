// A stand-in for telegram-web-app.js, served at the script's real address, so the production build runs unchanged
// under its content security policy. It implements the part of Telegram.WebApp the real adapter uses
// (src/platform/real.ts) and records the native controls for the test to read and press.
import type { Page } from '@playwright/test';
import { signedInitData } from './launch-data';

export const TELEGRAM_SCRIPT = 'https://telegram.org/js/telegram-web-app.js';

export type Scheme = 'light' | 'dark';

// Theme parameters as Telegram's mobile clients send them (https://core.telegram.org/bots/webapps#themeparams).
const THEMES: Record<Scheme, Record<string, string>> = {
  light: {
    bg_color: '#ffffff',
    secondary_bg_color: '#f0f0f0',
    section_bg_color: '#ffffff',
    header_bg_color: '#ffffff',
    bottom_bar_bg_color: '#ffffff',
    text_color: '#000000',
    hint_color: '#999999',
    subtitle_text_color: '#999999',
    section_header_text_color: '#3a95d5',
    link_color: '#2481cc',
    accent_text_color: '#2481cc',
    button_color: '#3390ec',
    button_text_color: '#ffffff',
    destructive_text_color: '#e53935',
  },
  dark: {
    bg_color: '#212d3b',
    secondary_bg_color: '#151e27',
    section_bg_color: '#1d2733',
    header_bg_color: '#242d39',
    bottom_bar_bg_color: '#151e27',
    text_color: '#ffffff',
    hint_color: '#7d8b99',
    subtitle_text_color: '#7e8b98',
    section_header_text_color: '#79c4fc',
    link_color: '#5eabe1',
    accent_text_color: '#64b5ef',
    button_color: '#50a8eb',
    button_text_color: '#ffffff',
    destructive_text_color: '#ee686f',
  },
};

export interface LaunchOptions {
  readonly startParam?: string;
  readonly scheme?: Scheme;
}

/** What the stub recorded of the native controls. */
export interface Chrome {
  readonly main: { readonly text: string; readonly visible: boolean; readonly enabled: boolean; readonly progress: boolean };
  readonly backVisible: boolean;
  readonly popups: readonly string[];
}

function stubSource(config: object): string {
  // Plain ES2020 that runs in the page; no markup is written, so the policy has nothing to refuse.
  return `(() => {
  const config = ${JSON.stringify(config)};
  const listeners = { main: new Set(), back: new Set() };
  const events = new Map();
  const state = { main: { text: '', visible: false, enabled: true, progress: false }, backVisible: false, popups: [], pending: [] };
  const on = (set) => (callback) => set.add(callback);
  const off = (set) => (callback) => set.delete(callback);
  const main = {
    setText: (text) => { state.main.text = text; },
    show: () => { state.main.visible = true; },
    hide: () => { state.main.visible = false; },
    enable: () => { state.main.enabled = true; },
    disable: () => { state.main.enabled = false; },
    showProgress: () => { state.main.progress = true; },
    hideProgress: () => { state.main.progress = false; },
    onClick: on(listeners.main),
    offClick: off(listeners.main),
  };
  const back = {
    show: () => { state.backVisible = true; },
    hide: () => { state.backVisible = false; },
    onClick: on(listeners.back),
    offClick: off(listeners.back),
  };
  const noop = () => {};
  window.Telegram = { WebApp: {
    initData: config.initData,
    initDataUnsafe: config.startParam === undefined ? {} : { start_param: config.startParam },
    version: '9.1',
    platform: 'android',
    colorScheme: config.scheme,
    themeParams: config.theme,
    viewportStableHeight: window.innerHeight,
    safeAreaInset: { top: 0, right: 0, bottom: 0, left: 0 },
    contentSafeAreaInset: { top: 0, right: 0, bottom: 0, left: 0 },
    MainButton: main,
    BackButton: back,
    HapticFeedback: { impactOccurred: noop, notificationOccurred: noop, selectionChanged: noop },
    isVersionAtLeast: (version) => Number(version) <= 9.1,
    onEvent: (name, callback) => { if (!events.has(name)) events.set(name, new Set()); events.get(name).add(callback); },
    offEvent: (name, callback) => { events.get(name)?.delete(callback); },
    ready: noop,
    expand: noop,
    close: noop,
    disableVerticalSwipes: noop,
    enableClosingConfirmation: noop,
    disableClosingConfirmation: noop,
    showPopup: (params, callback) => { state.popups.push(params.message); state.pending.push(callback); },
  } };
  window.__telegramStub = {
    chrome: () => JSON.parse(JSON.stringify({ main: state.main, backVisible: state.backVisible, popups: state.popups })),
    pressMain: () => { if (state.main.visible && state.main.enabled && !state.main.progress) listeners.main.forEach((f) => f()); },
    pressBack: () => listeners.back.forEach((f) => f()),
    answerPopup: (id) => { const callback = state.pending.shift(); if (callback) callback(id); },
  };
})();`;
}

/** Serves the stub for this page, with launch data signed now. */
export async function installTelegram(page: Page, options: LaunchOptions = {}): Promise<void> {
  const scheme = options.scheme ?? 'light';
  const body = stubSource({
    initData: signedInitData(options.startParam),
    startParam: options.startParam,
    scheme,
    theme: THEMES[scheme],
  });
  await page.route(TELEGRAM_SCRIPT, (route) => route.fulfill({ contentType: 'text/javascript', body }));
}

type StubWindow = Window & {
  __telegramStub: { chrome(): Chrome; pressMain(): void; pressBack(): void; answerPopup(id: string): void };
};

export function chrome(page: Page): Promise<Chrome> {
  return page.evaluate(() => (window as unknown as StubWindow).__telegramStub.chrome());
}

/** Presses Telegram's bottom button, as the user would below the page. */
export function pressMainButton(page: Page): Promise<void> {
  return page.evaluate(() => (window as unknown as StubWindow).__telegramStub.pressMain());
}

export function pressBackButton(page: Page): Promise<void> {
  return page.evaluate(() => (window as unknown as StubWindow).__telegramStub.pressBack());
}

/** Answers the open native popup with the button `id`, as the user would. */
export function answerPopup(page: Page, id: string): Promise<void> {
  return page.evaluate((button) => (window as unknown as StubWindow).__telegramStub.answerPopup(button), id);
}
