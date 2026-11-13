// Theme adapter: a pure function from themeParams and colorScheme to the semantic token set
// (MINI_APP 5.2 to 5.4). Components read --dg-* tokens only; nothing else reads themeParams.
import type { ColorScheme, ThemeParams } from './port';

export const TOKEN_NAMES = [
  'bg',
  'surface',
  'surface-alt',
  'text',
  'text-muted',
  'text-section',
  'separator',
  'accent',
  'on-accent',
  'accent-text',
  'danger',
  'focus',
  'bottom-bar',
  'domain-medication',
  'domain-hydration',
  'domain-nutrition',
  'domain-mood',
  'domain-sleep',
  'recorded',
] as const;

export type TokenName = (typeof TOKEN_NAMES)[number];
export type TokenSet = Readonly<Record<TokenName, string>>;

type Rgb = readonly [number, number, number];
type DataToken = Extract<TokenName, `domain-${string}` | 'recorded'>;
type Palette = Readonly<Record<Exclude<TokenName, 'focus'>, string>>;

const TEXT_TARGET = 4.5;
const GRAPHIC_TARGET = 3;
const MUTED_TARGET = 7;
const CORRECTION_STEP = 0.04;
const CORRECTION_STEPS = 12;
const BLACK = '#000000';
const WHITE = '#ffffff';

/** Dosegram's own values for absent keys (MINI_APP 5.2, 5.4). Not Telegram's defaults. */
export const FALLBACK: Readonly<Record<ColorScheme, Palette>> = {
  light: {
    bg: '#f0f2f5',
    surface: '#ffffff',
    'surface-alt': '#ffffff',
    text: '#111418',
    'text-muted': '#5b6470',
    'text-section': '#5b6470',
    separator: '#d9dde3',
    accent: '#1f6fd0',
    'on-accent': '#ffffff',
    'accent-text': '#1f6fd0',
    danger: '#c62828',
    'bottom-bar': '#f0f2f5',
    'domain-medication': '#2456c8',
    'domain-hydration': '#0b7f8c',
    'domain-nutrition': '#a85a00',
    'domain-mood': '#b0307a',
    'domain-sleep': '#6a42c2',
    recorded: '#1f7a4d',
  },
  dark: {
    bg: '#0f1113',
    surface: '#1f2226',
    'surface-alt': '#17191c',
    text: '#f2f4f7',
    'text-muted': '#a3abb5',
    'text-section': '#a3abb5',
    separator: '#2c3036',
    accent: '#2a72d0',
    'on-accent': '#ffffff',
    'accent-text': '#6cb0ff',
    danger: '#ff6b6b',
    'bottom-bar': '#0f1113',
    'domain-medication': '#7fa6ff',
    'domain-hydration': '#4cc4d2',
    'domain-nutrition': '#f0a04b',
    'domain-mood': '#f07ab8',
    'domain-sleep': '#b49cff',
    recorded: '#5fcf94',
  },
};

const DATA_TOKENS: readonly DataToken[] = [
  'domain-medication',
  'domain-hydration',
  'domain-nutrition',
  'domain-mood',
  'domain-sleep',
  'recorded',
];

function toRgb(hex: string): Rgb {
  const value = Number.parseInt(hex.slice(1), 16);
  return [(value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff];
}

function toHex(rgb: Rgb): string {
  return `#${rgb.map((channel) => Math.round(channel).toString(16).padStart(2, '0')).join('')}`;
}

function luminance(hex: string): number {
  const [r, g, b] = toRgb(hex).map((channel) => {
    const unit = channel / 255;
    return unit <= 0.03928 ? unit / 12.92 : ((unit + 0.055) / 1.055) ** 2.4;
  }) as [number, number, number];
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** WCAG 2 contrast ratio of two #rrggbb colours. */
export function contrast(a: string, b: string): number {
  const [light, dark] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
  return (light + 0.05) / (dark + 0.05);
}

function mix(from: string, to: string, share: number): string {
  const a = toRgb(from);
  const b = toRgb(to);
  return toHex([0, 1, 2].map((i) => a[i]! + (b[i]! - a[i]!) * share) as unknown as Rgb);
}

/** Theme values are untrusted input: only #rrggbb is accepted. */
function pick(params: ThemeParams, keys: readonly string[], fallback: string): string {
  for (const key of keys) {
    const value = params[key];
    if (value !== undefined && /^#[0-9a-f]{6}$/i.test(value)) return value.toLowerCase();
  }
  return fallback;
}

function meets(foreground: string, backgrounds: readonly string[], target: number): boolean {
  return backgrounds.every((background) => contrast(foreground, background) >= target);
}

function strongest(against: readonly string[]): string {
  const worst = (colour: string) => Math.min(...against.map((bg) => contrast(colour, bg)));
  return worst(BLACK) >= worst(WHITE) ? BLACK : WHITE;
}

/** Mixes the colour toward black or white in 4 percent steps until it meets the target. */
function correct(colour: string, against: readonly string[], target: number): string | null {
  const pole = strongest(against);
  for (let step = 0; step <= CORRECTION_STEPS; step += 1) {
    const candidate = mix(colour, pole, step * CORRECTION_STEP);
    if (meets(candidate, against, target)) return candidate;
  }
  return null;
}

function readable(colour: string, fallback: string, against: readonly string[]): string {
  const corrected = correct(colour, against, TEXT_TARGET);
  if (corrected) return corrected;
  return meets(fallback, against, TEXT_TARGET) ? fallback : strongest(against);
}

/** text_color mixed toward the surface, as close to 7:1 as possible and never below 4.5:1. */
function muted(text: string, surface: string): string {
  for (let share = 0; share < 1; share += 0.02) {
    const candidate = mix(text, surface, share);
    const ratio = contrast(candidate, surface);
    if (ratio > MUTED_TARGET) continue;
    return ratio >= TEXT_TARGET ? candidate : mix(text, surface, Math.max(0, share - 0.02));
  }
  return text;
}

function accentPair(params: ThemeParams, fallback: Palette): { accent: string; onAccent: string } {
  const onAccent = pick(params, ['button_text_color'], fallback['on-accent']);
  const accent = correct(pick(params, ['button_color'], fallback.accent), [onAccent], TEXT_TARGET);
  // correct() moves the foreground away from its background; here the accent is the adjusted side.
  return accent ? { accent, onAccent } : { accent: fallback.accent, onAccent: fallback['on-accent'] };
}

function dataColour(token: DataToken, scheme: ColorScheme, surface: string, text: string): string {
  const other: ColorScheme = scheme === 'light' ? 'dark' : 'light';
  const candidates = [FALLBACK[scheme][token], FALLBACK[other][token]];
  return candidates.find((colour) => contrast(colour, surface) >= GRAPHIC_TARGET) ?? text;
}

export function deriveTokens(params: ThemeParams, scheme: ColorScheme): TokenSet {
  const fallback = FALLBACK[scheme];
  const bg = pick(params, ['secondary_bg_color'], fallback.bg);
  const surface = pick(params, ['section_bg_color', 'bg_color'], fallback.surface);
  const text = readable(pick(params, ['text_color'], fallback.text), fallback.text, [surface, bg]);
  const accentText = readable(
    pick(params, ['accent_text_color', 'link_color'], fallback['accent-text']),
    fallback['accent-text'],
    [surface],
  );
  const { accent, onAccent } = accentPair(params, fallback);
  const data = Object.fromEntries(
    DATA_TOKENS.map((token) => [token, dataColour(token, scheme, surface, text)]),
  ) as Record<DataToken, string>;

  return {
    bg,
    surface,
    'surface-alt': pick(params, ['bg_color'], fallback['surface-alt']),
    text,
    'text-muted': muted(text, surface),
    'text-section': readable(
      pick(params, ['section_header_text_color'], fallback['text-section']),
      fallback['text-section'],
      [bg],
    ),
    separator: pick(params, ['section_separator_color'], fallback.separator),
    accent,
    'on-accent': onAccent,
    'accent-text': accentText,
    danger: readable(pick(params, ['destructive_text_color'], fallback.danger), fallback.danger, [surface]),
    focus: accentText,
    'bottom-bar': pick(params, ['bottom_bar_bg_color', 'secondary_bg_color'], fallback['bottom-bar']),
    ...data,
  };
}
