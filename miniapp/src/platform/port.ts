// The one seam to Telegram (MINI_APP 14.1, MA-STACK-2). Only platform/ may touch window.Telegram.
// This scaffold covers the subset the screens use; the remaining members of the 14.1 sketch
// (secondaryButton, settingsButton, homeScreen, biometric) join with the slices that need them.

export type Unsubscribe = () => void;
export type ColorScheme = 'light' | 'dark';
export type PerformanceClass = 'LOW' | 'AVERAGE' | 'HIGH' | 'UNKNOWN';
export type ThemeParams = Readonly<Record<string, string>>;

export interface Insets {
  readonly top: number;
  readonly right: number;
  readonly bottom: number;
  readonly left: number;
}

export interface ViewportInsets {
  readonly safe: Insets;
  readonly content: Insets;
  readonly stableHeight: number;
}

export type HapticEvent =
  | 'success'
  | 'error'
  | 'warning'
  | 'selection'
  | 'impact-light';

export interface PopupButton {
  readonly id: string;
  readonly text: string;
  readonly kind?: 'default' | 'destructive';
}

export interface PopupParams {
  readonly title?: string;
  readonly message: string;
  readonly buttons: readonly PopupButton[];
}

export interface BottomButtonPort {
  setText(text: string): void;
  show(): void;
  hide(): void;
  setEnabled(enabled: boolean): void;
  setProgress(inProgress: boolean): void;
  onClick(listener: () => void): Unsubscribe;
}

/** The colours of Telegram's own header, WebView background and bottom bar (TGF-10), as #rrggbb. */
export interface ChromeColors {
  readonly header: string;
  readonly background: string;
  readonly bottomBar: string;
}

/** The CloudStorage keys of MINI_APP 6.6. Interface preferences only: never health data, never a credential. */
export type PreferenceKey = 'ui_v1_insights_range';

/**
 * Interface preferences kept per device by Telegram's CloudStorage (MINI_APP 6.6). Where the client has
 * no CloudStorage, nothing is kept and every read finds nothing.
 */
export interface PreferencesPort {
  /** The stored value; undefined when absent, unsupported, failed or slower than 500 ms (MA-STORE-2). */
  get(key: PreferenceKey): Promise<string | undefined>;
  /** Stores the value without waiting; a failure only means the preference is not kept. */
  set(key: PreferenceKey, value: string): void;
}

export interface BackButtonPort {
  show(): void;
  hide(): void;
  onClick(listener: () => void): Unsubscribe;
}

export interface TelegramPort {
  readonly initData: string;
  readonly startParam: string | undefined;
  readonly platform: string;
  readonly performanceClass: PerformanceClass;
  isVersionAtLeast(version: string): boolean;

  theme(): { scheme: ColorScheme; params: ThemeParams };
  onThemeChanged(listener: () => void): Unsubscribe;
  insets(): ViewportInsets;
  onViewportChanged(listener: () => void): Unsubscribe;
  onActivated(listener: () => void): Unsubscribe;

  ready(): void;
  expand(): void;
  /** Matches Telegram's header, background and bottom bar to the page (MINI_APP 3.2 step 4). */
  setChromeColors(colors: ChromeColors): void;
  disableVerticalSwipes(): void;
  setClosingConfirmation(enabled: boolean): void;
  /** Closes the Mini App; the one action of a gate screen (MINI_APP 3.5). */
  close(): void;

  readonly mainButton: BottomButtonPort;
  readonly backButton: BackButtonPort;
  readonly preferences: PreferencesPort;

  haptic(event: HapticEvent): void;
  /** Resolves with the pressed button id, or null when the popup was dismissed. */
  popup(params: PopupParams): Promise<string | null>;
  /**
   * Hands a file URL to the Telegram client's download manager (Bot API 8.0 downloadFile,
   * MA-NATIVE-13). Resolves true when the person accepted, false when declined or unsupported.
   */
  downloadFile(params: DownloadFileParams): Promise<boolean>;
}

export interface DownloadFileParams {
  readonly url: string;
  readonly fileName: string;
}

export const ZERO_INSETS: Insets = { top: 0, right: 0, bottom: 0, left: 0 };
