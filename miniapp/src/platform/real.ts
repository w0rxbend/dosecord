import {
  ZERO_INSETS,
  type BackButtonPort,
  type BottomButtonPort,
  type HapticEvent,
  type Insets,
  type PerformanceClass,
  type TelegramPort,
  type Unsubscribe,
} from './port';

// The part of window.Telegram.WebApp this adapter uses (https://core.telegram.org/bots/webapps).
type Callback = () => void;

interface NativeButton {
  setText(text: string): void;
  show(): void;
  hide(): void;
  enable(): void;
  disable(): void;
  showProgress(leaveActive?: boolean): void;
  hideProgress(): void;
  onClick(callback: Callback): void;
  offClick(callback: Callback): void;
}

interface NativeWebApp {
  initData: string;
  initDataUnsafe: { start_param?: string };
  platform: string;
  colorScheme: 'light' | 'dark';
  themeParams: Record<string, string>;
  viewportStableHeight: number;
  safeAreaInset?: Insets;
  contentSafeAreaInset?: Insets;
  MainButton: NativeButton;
  BackButton: Pick<NativeButton, 'show' | 'hide' | 'onClick' | 'offClick'>;
  HapticFeedback: {
    impactOccurred(style: 'light'): void;
    notificationOccurred(type: 'success' | 'error' | 'warning'): void;
    selectionChanged(): void;
  };
  isVersionAtLeast(version: string): boolean;
  onEvent(event: string, callback: Callback): void;
  offEvent(event: string, callback: Callback): void;
  ready(): void;
  expand(): void;
  close(): void;
  disableVerticalSwipes?(): void;
  enableClosingConfirmation(): void;
  disableClosingConfirmation(): void;
  showPopup(
    params: { title?: string; message: string; buttons: { id: string; type: string; text: string }[] },
    callback: (buttonId: string | null) => void,
  ): void;
  downloadFile?(params: { url: string; file_name: string }, callback: (accepted: boolean) => void): void;
}

declare global {
  interface Window {
    Telegram?: { WebApp?: NativeWebApp };
  }
}

const VIEWPORT_EVENTS = ['viewportChanged', 'safeAreaChanged', 'contentSafeAreaChanged'];

export function parsePerformanceClass(userAgent: string): PerformanceClass {
  const match = /\b(LOW|AVERAGE|HIGH)\)/.exec(userAgent);
  return (match?.[1] as PerformanceClass | undefined) ?? 'UNKNOWN';
}

function subscribe(app: NativeWebApp, events: readonly string[], listener: Callback): Unsubscribe {
  for (const event of events) app.onEvent(event, listener);
  return () => {
    for (const event of events) app.offEvent(event, listener);
  };
}

function clickable(button: Pick<NativeButton, 'onClick' | 'offClick'>) {
  return (listener: Callback): Unsubscribe => {
    button.onClick(listener);
    return () => button.offClick(listener);
  };
}

function bottomButton(native: NativeButton): BottomButtonPort {
  return {
    setText: (text) => native.setText(text),
    show: () => native.show(),
    hide: () => native.hide(),
    setEnabled: (enabled) => (enabled ? native.enable() : native.disable()),
    // showProgress(false) also deactivates the button while the request runs (MA-NATIVE-2).
    setProgress: (inProgress) => (inProgress ? native.showProgress(false) : native.hideProgress()),
    onClick: clickable(native),
  };
}

function fireHaptic(app: NativeWebApp, event: HapticEvent): void {
  switch (event) {
    case 'selection':
      return app.HapticFeedback.selectionChanged();
    case 'impact-light':
      return app.HapticFeedback.impactOccurred('light');
    default:
      return app.HapticFeedback.notificationOccurred(event);
  }
}

/** Returns the adapter over the official script, or null when the script did not load. */
export function createRealTelegramPort(host: Window = window): TelegramPort | null {
  const app = host.Telegram?.WebApp;
  if (!app) return null;

  const backButton: BackButtonPort = {
    show: () => app.BackButton.show(),
    hide: () => app.BackButton.hide(),
    onClick: clickable(app.BackButton),
  };

  return {
    initData: app.initData,
    startParam: app.initDataUnsafe.start_param,
    platform: app.platform,
    performanceClass: parsePerformanceClass(host.navigator.userAgent),
    isVersionAtLeast: (version) => app.isVersionAtLeast(version),

    theme: () => ({ scheme: app.colorScheme, params: app.themeParams }),
    onThemeChanged: (listener) => subscribe(app, ['themeChanged'], listener),
    insets: () => ({
      safe: app.safeAreaInset ?? ZERO_INSETS,
      content: app.contentSafeAreaInset ?? ZERO_INSETS,
      stableHeight: app.viewportStableHeight,
    }),
    onViewportChanged: (listener) => subscribe(app, VIEWPORT_EVENTS, listener),
    onActivated: (listener) => subscribe(app, ['activated'], listener),

    ready: () => app.ready(),
    expand: () => app.expand(),
    disableVerticalSwipes: () => app.disableVerticalSwipes?.(),
    setClosingConfirmation: (enabled) =>
      enabled ? app.enableClosingConfirmation() : app.disableClosingConfirmation(),
    close: () => app.close(),

    mainButton: bottomButton(app.MainButton),
    backButton,

    haptic: (event) => fireHaptic(app, event),
    popup: (params) =>
      new Promise((resolve) => {
        const buttons = params.buttons.map((button) => ({
          id: button.id,
          type: button.kind ?? 'default',
          text: button.text,
        }));
        app.showPopup({ title: params.title, message: params.message, buttons }, resolve);
      }),
    downloadFile: (params) =>
      new Promise((resolve) => {
        if (!app.downloadFile || !app.isVersionAtLeast('8.0')) {
          resolve(false);
          return;
        }
        app.downloadFile({ url: params.url, file_name: params.fileName }, (accepted) => resolve(accepted));
      }),
  };
}
