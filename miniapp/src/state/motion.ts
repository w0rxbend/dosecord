import type { TelegramPort } from '../platform/port';

/**
 * Reduced motion applies for the system setting, for performance class LOW, or when the root is
 * marked (MA-DS-5). Then transitions cut and the blob neither breathes nor morphs.
 */
export function isReducedMotion(tg: TelegramPort): boolean {
  if (tg.performanceClass === 'LOW') return true;
  if (document.documentElement.dataset.reducedMotion === 'true') return true;
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}
