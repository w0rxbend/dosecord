import type { TelegramPort } from '../platform/port';

export interface MainButtonOptions {
  readonly text: string;
  readonly enabled: () => boolean;
  readonly busy: () => boolean;
  readonly onPress: () => void;
}

/**
 * Owns the native bottom button for the life of a task screen. Exactly one click handler is
 * registered and it is removed when the screen leaves (MA-NATIVE-1). While a save runs the
 * button shows progress, which also deactivates it (MA-NATIVE-2). Call during component setup.
 */
export function useMainButton(tg: TelegramPort, options: MainButtonOptions): void {
  $effect(() => {
    tg.mainButton.setText(options.text);
    tg.mainButton.show();
    const off = tg.mainButton.onClick(options.onPress);
    return () => {
      off();
      tg.mainButton.setProgress(false);
      tg.mainButton.hide();
    };
  });
  $effect(() => tg.mainButton.setEnabled(options.enabled()));
  $effect(() => tg.mainButton.setProgress(options.busy()));
}

/**
 * Asks before a dirty form is left, with the native popup (MA-NAV-5, MA-DS-2), and keeps the
 * closing confirmation in step with the form (MA-NATIVE-9). Call during component setup.
 */
export function useLeaveGuard(
  tg: TelegramPort,
  setGuard: (guard: (() => Promise<boolean>) | null) => void,
  isDirty: () => boolean,
  copy: { title: string; message: string; stay: string; leave: string },
): void {
  $effect(() => {
    const dirty = isDirty();
    tg.setClosingConfirmation(dirty);
    setGuard(
      dirty
        ? async () => {
            tg.haptic('warning');
            const answer = await tg.popup({
              title: copy.title,
              message: copy.message,
              buttons: [
                { id: 'stay', text: copy.stay },
                { id: 'leave', text: copy.leave, kind: 'destructive' },
              ],
            });
            return answer === 'leave';
          }
        : null,
    );
    return () => {
      tg.setClosingConfirmation(false);
      setGuard(null);
    };
  });
}
