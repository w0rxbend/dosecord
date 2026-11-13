// Drawing the mood blob from the server's asset (MINI_APP 7.9). The geometry is the server's: the
// client holds no copy of the generating formula, only the arithmetic to paint and blend the
// numbers it was given, so the blob in the app and the one in the chat image cannot drift.
import type { BlobAsset, BlobColours, BlobState } from '../api/types';

export const NO_ENTRY = 'blob.none';

/** A stored valence step reaches the drawn levels V1, V2, V4, V6 and V7 (VISUALS 4.3). */
const LEVEL_OF_STEP = [1, 2, 4, 6, 7];
const MIDDLE_ENERGY = 3;

/** The drawn state of a check-in; a check-in without energy is drawn at the middle energy level. */
export function stateIdOf(valence: number, energy: number | undefined): string {
  return `blob.v${LEVEL_OF_STEP[valence - 1] ?? 4}.e${energy ?? MIDDLE_ENERGY}`;
}

export function findState(asset: BlobAsset, id: string): BlobState | undefined {
  return asset.states.find((state) => state.id === id) ?? asset.states.find((state) => state.id === NO_ENTRY);
}

const round = (value: number): number => Math.round(value * 10_000) / 10_000;

/** A closed path: the start point, then six numbers per cubic segment. */
export function outlinePath(outline: readonly number[]): string {
  if (outline.length < 2) return '';
  const parts = [`M${round(outline[0]!)} ${round(outline[1]!)}`];
  for (let index = 2; index + 5 < outline.length; index += 6) {
    const [x1, y1, x2, y2, x, y] = outline.slice(index, index + 6).map(round);
    parts.push(`C${x1} ${y1} ${x2} ${y2} ${x} ${y}`);
  }
  return `${parts.join('')}Z`;
}

/** The mouth: a quadratic curve `x1 y1, control, x2 y2`. */
export function mouthPath(mouth: readonly number[]): string {
  const [x1, y1, cx, cy, x2, y2] = mouth.map(round);
  return mouth.length === 6 ? `M${x1} ${y1}Q${cx} ${cy} ${x2} ${y2}` : '';
}

/**
 * Two outlines blend number by number only when they have the same segments (MA-MOOD-5). Brows
 * that appear or go are not blended: the target's are drawn.
 */
export function canBlend(from: BlobState, to: BlobState): boolean {
  return from.outline.length === to.outline.length && (from.face === undefined) === (to.face === undefined);
}

const lerp = (from: number, to: number, t: number): number => from + (to - from) * t;

function blendNumbers(from: readonly number[], to: readonly number[], t: number): number[] {
  return to.map((value, index) => lerp(from[index] ?? value, value, t));
}

function channel(hex: string, offset: number): number {
  return Number.parseInt(hex.slice(offset, offset + 2), 16);
}

export function blendColour(from: string, to: string, t: number): string {
  const mixed = [1, 3, 5].map((offset) => Math.round(lerp(channel(from, offset), channel(to, offset), t)));
  return `#${mixed.map((value) => value.toString(16).padStart(2, '0')).join('')}`;
}

function blendColours(from: BlobColours, to: BlobColours, t: number): BlobColours {
  return {
    fillInner: blendColour(from.fillInner, to.fillInner, t),
    fillOuter: blendColour(from.fillOuter, to.fillOuter, t),
    outline: blendColour(from.outline, to.outline, t),
    ground: blendColour(from.ground, to.ground, t),
    background: blendColour(from.background, to.background, t),
  };
}

/** The state between two states at `t` from 0 to 1; callers check [[canBlend]] first. */
export function blend(from: BlobState, to: BlobState, t: number): BlobState {
  const face =
    from.face && to.face
      ? {
          ink: blendColour(from.face.ink, to.face.ink, t),
          eyes: to.face.eyes.map((eye, index) => blendNumbers(from.face?.eyes[index] ?? eye, eye, t)),
          mouth: blendNumbers(from.face.mouth, to.face.mouth, t),
          strokeWidth: lerp(from.face.strokeWidth, to.face.strokeWidth, t),
          brows:
            from.face.brows.length === to.face.brows.length
              ? to.face.brows.map((brow, index) => blendNumbers(from.face?.brows[index] ?? brow, brow, t))
              : to.face.brows,
        }
      : to.face;
  return {
    ...to,
    outline: blendNumbers(from.outline, to.outline, t),
    gradient: blendNumbers(from.gradient, to.gradient, t),
    ground: blendNumbers(from.ground, to.ground, t),
    outlineWidth: lerp(from.outlineWidth, to.outlineWidth, t),
    light: blendColours(from.light, to.light, t),
    dark: blendColours(from.dark, to.dark, t),
    face,
  };
}
