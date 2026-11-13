export interface ToastAction {
  readonly label: string;
  readonly run: () => void;
}

export interface Toast {
  readonly message: string;
  readonly action?: ToastAction;
}

const VISIBLE_MS = 8000;

/** One outcome message at a time, with an optional Undo, visible for 8 seconds (MA-TODAY-2). */
export class Toasts {
  current = $state.raw<Toast | null>(null);
  private timer: ReturnType<typeof setTimeout> | undefined;

  show(message: string, action?: ToastAction): void {
    clearTimeout(this.timer);
    this.current = { message, action };
    this.timer = setTimeout(() => this.dismiss(), VISIBLE_MS);
  }

  dismiss(): void {
    clearTimeout(this.timer);
    this.current = null;
  }
}
