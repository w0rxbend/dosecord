// The data palette of the Mini App is the R5 palette of the images (VISUALS 3.3.2; supervisor
// decision, 2026-11-28): Spruce, Tide, Rye, Beet and Dusk, each at 3:1 against every surface of its
// scheme for normal vision, in greyscale and with deuteranopia or protanopia (Machado, Oliveira and
// Fernandes 2009 at severity 1.0, the matrices of the renderer's ColourVisionSuite).
// The renderer's generated stylesheet, the one source of the image palette (ROADMAP G4.1).
import rendererCss from '../../../report-renderer/test/resources/goldens/tokens.css?raw';
import appCss from '../styles/tokens.css?raw';
import { describe, expect, it } from 'vitest';
import { FALLBACK, contrast } from './theme';

type Matrix = readonly [number, number, number, number, number, number, number, number, number];

const VISIONS: Readonly<Record<string, Matrix>> = {
  normal: [1, 0, 0, 0, 1, 0, 0, 0, 1],
  greyscale: [0.2126, 0.7152, 0.0722, 0.2126, 0.7152, 0.0722, 0.2126, 0.7152, 0.0722],
  deuteranopia: [0.367322, 0.860646, -0.227968, 0.280085, 0.672501, 0.047413, -0.01182, 0.04294, 0.968881],
  protanopia: [0.152286, 1.052583, -0.204868, 0.114503, 0.786281, 0.099216, -0.003882, -0.048116, 1.051998],
};

const linear = (channel: number): number => {
  const unit = channel / 255;
  return unit <= 0.04045 ? unit / 12.92 : ((unit + 0.055) / 1.055) ** 2.4;
};

const encode = (value: number): number => {
  const clamped = Math.min(1, Math.max(0, value));
  const unit = clamped <= 0.0031308 ? clamped * 12.92 : 1.055 * clamped ** (1 / 2.4) - 0.055;
  return Math.round(unit * 255);
};

/** A #rrggbb colour as a reader with this vision sees it. */
function seen(hex: string, matrix: Matrix): string {
  const value = Number.parseInt(hex.slice(1), 16);
  const rgb = [(value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff].map(linear);
  const out = [0, 1, 2].map((row) => encode(rgb.reduce((sum, channel, column) => sum + matrix[row * 3 + column]! * channel, 0)));
  return `#${out.map((channel) => channel.toString(16).padStart(2, '0')).join('')}`;
}

const DOMAINS = ['domain-medication', 'domain-hydration', 'domain-nutrition', 'domain-mood', 'domain-sleep'] as const;
const SURFACES = ['surface', 'surface-alt', 'bg'] as const;

/** The domain hues of the renderer's generated stylesheet, by scheme. */
function rendererHues(): Record<'light' | 'dark', Record<string, string>> {
  const [light, dark] = rendererCss.split('[data-scheme="dark"]') as [string, string];
  const hues = (block: string): Record<string, string> =>
    Object.fromEntries([...block.matchAll(/--dg-(spruce|tide|rye|beet|dusk): (#[0-9a-f]{6});/g)].map((m) => [m[1]!, m[2]!]));
  return { light: hues(light), dark: hues(dark) };
}

describe('the data palette (R5)', () => {
  it('keeps every domain hue at 3:1 on every surface of its scheme, for every reader', () => {
    for (const scheme of ['light', 'dark'] as const) {
      const palette = FALLBACK[scheme];
      for (const token of DOMAINS) {
        for (const surface of SURFACES) {
          for (const [vision, matrix] of Object.entries(VISIONS)) {
            const ratio = contrast(seen(palette[token], matrix), seen(palette[surface], matrix));
            expect(ratio, `${scheme} ${token} on ${surface}, ${vision}`).toBeGreaterThanOrEqual(3);
          }
        }
      }
    }
  });

  it('is the palette of the images, with dark Tide one step lighter for the lighter surface of the app', () => {
    const renderer = rendererHues();
    const names = { 'domain-medication': 'spruce', 'domain-hydration': 'tide', 'domain-nutrition': 'rye', 'domain-mood': 'beet', 'domain-sleep': 'dusk' };
    for (const scheme of ['light', 'dark'] as const) {
      for (const token of DOMAINS) {
        const image = renderer[scheme][names[token]];
        expect(image, `${scheme} ${names[token]} in tokens.css`).toMatch(/^#[0-9a-f]{6}$/);
        // Tide 500 of the ramp of VISUALS 3.3.2: the image's dark Tide falls to 2.9:1 on #1f2226 with deuteranopia.
        const expected = scheme === 'dark' && token === 'domain-hydration' ? '#1e85d4' : image;
        expect(FALLBACK[scheme][token], `${scheme} ${token}`).toBe(expected);
      }
    }
  });

  it('writes the same hues into the first-paint stylesheet', () => {
    const [light, dark] = appCss.split(":root[data-scheme='dark']") as [string, string];
    for (const token of DOMAINS) {
      expect(light, token).toContain(`--dg-${token}: ${FALLBACK.light[token]};`);
      expect(dark, token).toContain(`--dg-${token}: ${FALLBACK.dark[token]};`);
    }
  });
});
