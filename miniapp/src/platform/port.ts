// The one seam to Telegram (MINI_APP 14.1, MA-STACK-2). Only platform/ may touch window.Telegram.
// This scaffold covers the subset the first screens use; the remaining members of the 14.1 sketch
// (secondaryButton, settingsButton, preferences, downloadFile, homeScreen, biometric) join with
// the slices that need them.

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
  disableVerticalSwipes(): void;
  setClosingConfirmation(enabled: boolean): void;

  readonly mainButton: BottomButtonPort;
  readonly backButton: BackButtonPort;

  haptic(event: HapticEvent): void;
  /** Resolves with the pressed button id, or null when the popup was dismissed. */
  popup(params: PopupParams): Promise<string | null>;
}

export const ZERO_INSETS: Insets = { top: 0, right: 0, bottom: 0, left: 0 };
