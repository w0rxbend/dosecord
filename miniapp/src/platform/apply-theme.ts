import type { TelegramPort, Unsubscribe } from './port';
import { TOKEN_NAMES, deriveTokens } from './theme';

function applyTheme(tg: TelegramPort, root: HTMLElement): void {
  const { scheme, params } = tg.theme();
  const tokens = deriveTokens(params, scheme);
  root.dataset.scheme = scheme;
  for (const name of TOKEN_NAMES) root.style.setProperty(`--dg-${name}`, tokens[name]);
}

function applyInsets(tg: TelegramPort, root: HTMLElement): void {
  const { safe, content, stableHeight } = tg.insets();
  for (const side of ['top', 'right', 'bottom', 'left'] as const) {
    root.style.setProperty(`--dg-inset-${side}`, `${safe[side] + content[side]}px`);
  }
  root.style.setProperty('--dg-stable-height', `${stableHeight}px`);
}

/**
 * Applies tokens and insets before the first paint and keeps them current without a reload
 * (MA-THEME-1, MA-LAYOUT-1, MA-LAYOUT-2). Reduced motion follows the performance class (MA-DS-5).
 */
export function bindAppearance(tg: TelegramPort, root: HTMLElement): Unsubscribe {
  applyTheme(tg, root);
  applyInsets(tg, root);
  if (tg.performanceClass === 'LOW') root.dataset.reducedMotion = 'true';

  const offTheme = tg.onThemeChanged(() => applyTheme(tg, root));
  const offViewport = tg.onViewportChanged(() => applyInsets(tg, root));
  return () => {
    offTheme();
    offViewport();
  };
}
