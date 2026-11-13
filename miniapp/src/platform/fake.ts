// DOSEGRAM_FAKE_TELEGRAM_PORT: marker searched by the bundle check; this file must not ship (MA-TEST-2).
import {
  ZERO_INSETS,
  type BottomButtonPort,
  type ColorScheme,
  type HapticEvent,
  type PerformanceClass,
  type PopupParams,
  type TelegramPort,
  type ThemeParams,
  type Unsubscribe,
  type ViewportInsets,
} from './port';

type Listener = () => void;

class Emitter {
  private readonly listeners = new Set<Listener>();

  on = (listener: Listener): Unsubscribe => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  emit(): void {
    for (const listener of [...this.listeners]) listener();
  }

  get size(): number {
    return this.listeners.size;
  }
}

export interface FakeButtonState {
  text: string;
  visible: boolean;
  enabled: boolean;
  inProgress: boolean;
}

export class FakeBottomButton implements BottomButtonPort {
  readonly state: FakeButtonState = { text: '', visible: false, enabled: true, inProgress: false };
  private readonly clicks = new Emitter();

  constructor(private readonly changed: Listener) {}

  private update(patch: Partial<FakeButtonState>): void {
    Object.assign(this.state, patch);
    this.changed();
  }

  setText = (text: string): void => this.update({ text });
  show = (): void => this.update({ visible: true });
  hide = (): void => this.update({ visible: false });
  setEnabled = (enabled: boolean): void => this.update({ enabled });
  setProgress = (inProgress: boolean): void => this.update({ inProgress });
  onClick = (listener: Listener): Unsubscribe => this.clicks.on(listener);

  get handlerCount(): number {
    return this.clicks.size;
  }

  /** What a tap on the native button does: nothing while hidden, disabled or in progress. */
  press(): void {
    const { visible, enabled, inProgress } = this.state;
    if (visible && enabled && !inProgress) this.clicks.emit();
  }
}

export class FakeBackButton {
  visible = false;
  private readonly clicks = new Emitter();

  constructor(private readonly changed: Listener) {}

  show = (): void => {
    this.visible = true;
    this.changed();
  };
  hide = (): void => {
    this.visible = false;
    this.changed();
  };
  onClick = (listener: Listener): Unsubscribe => this.clicks.on(listener);

  press(): void {
    if (this.visible) this.clicks.emit();
  }
}

export interface FakeTelegramOptions {
  initData?: string;
  startParam?: string;
  platform?: string;
  performanceClass?: PerformanceClass;
  scheme?: ColorScheme;
  params?: ThemeParams;
  insets?: ViewportInsets;
  /** Answers every popup with this button id; null dismisses it. */
  popupAnswer?: string | null;
}

/** In-memory TelegramPort for browser development and tests. It records what the app asked for. */
export class FakeTelegramPort implements TelegramPort {
  readonly initData: string;
  readonly startParam: string | undefined;
  readonly platform: string;
  readonly performanceClass: PerformanceClass;

  readonly haptics: HapticEvent[] = [];
  readonly popups: PopupParams[] = [];
  popupAnswer: string | null;
  readyCalls = 0;
  expanded = false;
  verticalSwipesDisabled = false;
  closingConfirmation = false;

  private scheme: ColorScheme;
  private params: ThemeParams;
  private viewport: ViewportInsets;
  private readonly themeChanged = new Emitter();
  private readonly viewportChanged = new Emitter();
  private readonly activated = new Emitter();
  private readonly chromeChanged = new Emitter();

  readonly mainButton = new FakeBottomButton(() => this.chromeChanged.emit());
  readonly backButton = new FakeBackButton(() => this.chromeChanged.emit());

  constructor(options: FakeTelegramOptions = {}) {
    this.initData = options.initData ?? 'fake-init-data';
    this.startParam = options.startParam;
    this.platform = options.platform ?? 'fake';
    this.performanceClass = options.performanceClass ?? 'UNKNOWN';
    this.scheme = options.scheme ?? 'light';
    this.params = options.params ?? {};
    this.viewport = options.insets ?? { safe: ZERO_INSETS, content: ZERO_INSETS, stableHeight: 640 };
    this.popupAnswer = options.popupAnswer ?? null;
  }

  isVersionAtLeast = (): boolean => true;
  theme = () => ({ scheme: this.scheme, params: this.params });
  onThemeChanged = (listener: Listener): Unsubscribe => this.themeChanged.on(listener);
  insets = (): ViewportInsets => this.viewport;
  onViewportChanged = (listener: Listener): Unsubscribe => this.viewportChanged.on(listener);
  onActivated = (listener: Listener): Unsubscribe => this.activated.on(listener);

  ready = (): void => {
    this.readyCalls += 1;
  };
  expand = (): void => {
    this.expanded = true;
  };
  disableVerticalSwipes = (): void => {
    this.verticalSwipesDisabled = true;
  };
  setClosingConfirmation = (enabled: boolean): void => {
    this.closingConfirmation = enabled;
  };

  haptic = (event: HapticEvent): void => {
    this.haptics.push(event);
  };
  popup = (params: PopupParams): Promise<string | null> => {
    this.popups.push(params);
    return Promise.resolve(this.popupAnswer);
  };

  // Controls for the development frame and for tests.
  setTheme(scheme: ColorScheme, params: ThemeParams = {}): void {
    this.scheme = scheme;
    this.params = params;
    this.themeChanged.emit();
  }

  setInsets(viewport: ViewportInsets): void {
    this.viewport = viewport;
    this.viewportChanged.emit();
  }

  activate(): void {
    this.activated.emit();
  }

  /** Notifies when the native chrome (bottom button, back button) changes. */
  onChromeChanged = (listener: Listener): Unsubscribe => this.chromeChanged.on(listener);
}
