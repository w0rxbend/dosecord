import { describe, expect, it } from 'vitest';
import { FALLBACK, contrast, deriveTokens } from './theme';

const LOW_CONTRAST = {
  bg_color: '#aaaaaa',
  secondary_bg_color: '#a0a0a0',
  section_bg_color: '#aaaaaa',
  text_color: '#999999',
  link_color: '#9a9aa5',
  button_color: '#eeeeee',
  button_text_color: '#ffffff',
  destructive_text_color: '#b09090',
  section_header_text_color: '#989898',
};

describe('deriveTokens', () => {
  it('uses the fallback table when themeParams is empty', () => {
    const tokens = deriveTokens({}, 'light');

    expect(tokens.bg).toBe(FALLBACK.light.bg);
    expect(tokens.surface).toBe(FALLBACK.light.surface);
    expect(tokens.text).toBe(FALLBACK.light.text);
    expect(tokens.accent).toBe(FALLBACK.light.accent);
    expect(tokens['domain-hydration']).toBe(FALLBACK.light['domain-hydration']);
  });

  it('binds surfaces and text to themeParams', () => {
    const tokens = deriveTokens(
      { secondary_bg_color: '#101418', section_bg_color: '#1C2026', text_color: '#FAFAFA' },
      'dark',
    );

    expect(tokens.bg).toBe('#101418');
    expect(tokens.surface).toBe('#1c2026');
    expect(tokens.text).toBe('#fafafa');
  });

  it('ignores values that are not #rrggbb', () => {
    const tokens = deriveTokens({ text_color: 'red', bg_color: 'url(javascript:1)' }, 'light');

    expect(tokens.text).toBe(FALLBACK.light.text);
    expect(tokens['surface-alt']).toBe(FALLBACK.light['surface-alt']);
  });

  it.each(['light', 'dark'] as const)('meets every text target for the %s fallback', (scheme) => {
    const t = deriveTokens({}, scheme);

    expect(contrast(t.text, t.surface)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(t.text, t.bg)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(t['accent-text'], t.surface)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(t.danger, t.surface)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(t['text-section'], t.bg)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(t['on-accent'], t.accent)).toBeGreaterThanOrEqual(4.5);
  });

  it('corrects a deliberately low-contrast theme to the targets', () => {
    const t = deriveTokens(LOW_CONTRAST, 'light');

    expect(contrast(t.text, t.surface)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(t.text, t.bg)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(t['accent-text'], t.surface)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(t.danger, t.surface)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(t['text-section'], t.bg)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(t['on-accent'], t.accent)).toBeGreaterThanOrEqual(4.5);
  });

  it('computes muted text between 4.5:1 and 7:1 instead of using hint_color', () => {
    const t = deriveTokens({ hint_color: '#eeeeee' }, 'light');
    const ratio = contrast(t['text-muted'], t.surface);

    expect(t['text-muted']).not.toBe('#eeeeee');
    expect(ratio).toBeGreaterThanOrEqual(4.5);
    expect(ratio).toBeLessThanOrEqual(7);
  });

  it('keeps data colours at 3:1 by swapping to the other scheme, then to text', () => {
    // A light scheme whose surface is dark: the light data colours fail, the dark ones pass.
    const swapped = deriveTokens({ section_bg_color: '#1f2226', text_color: '#f2f4f7' }, 'light');
    // A mid-grey surface on which neither variant of the sleep colour reaches 3:1.
    const grey = deriveTokens({ section_bg_color: '#8f79d8' }, 'light');

    expect(swapped['domain-medication']).toBe(FALLBACK.dark['domain-medication']);
    expect(grey['domain-sleep']).toBe(grey.text);
    expect(contrast(grey['domain-sleep'], grey.surface)).toBeGreaterThanOrEqual(3);
  });
});
