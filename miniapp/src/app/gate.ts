import { toApiError } from '../api/client';

/** The gate screens of MINI_APP 3.5: G-1, G-2, G-3, G-4 and G-6. */
export type GateKind = 'outside' | 'oldClient' | 'accountRequired' | 'sessionEnded' | 'offline';

/** Which gate a failed session exchange leads to. */
export function gateFor(cause: unknown): GateKind {
  switch (toApiError(cause).code) {
    case 'account_required':
      return 'accountRequired';
    case 'client_too_old':
      return 'oldClient';
    case 'network':
    case 'timeout':
    case 'server':
      return 'offline';
    default:
      return 'sessionEnded';
  }
}
