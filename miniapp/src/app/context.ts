import { getContext, setContext } from 'svelte';
import type { ApiClient } from '../api/client';
import type { TelegramPort } from '../platform/port';
import type { Toasts } from '../state/toasts.svelte';
import type { Router } from './router.svelte';

/** What every screen needs from the shell. Screens never import a concrete adapter. */
export interface AppContext {
  readonly api: ApiClient;
  readonly tg: TelegramPort;
  readonly router: Router;
  readonly toasts: Toasts;
}

export const APP_CONTEXT = Symbol('dosegram-app');

export function provideApp(context: AppContext): void {
  setContext(APP_CONTEXT, context);
}

export function useApp(): AppContext {
  return getContext<AppContext>(APP_CONTEXT);
}
