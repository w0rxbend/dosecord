import { describe, expect, it } from 'vitest';
import type { BlobAsset, BlobState } from '../api/types';
import asset from '../mock/blob-states.json';
import { blend, blendColour, canBlend, findState, mouthPath, outlinePath, stateIdOf } from './blob';

// The fixture is the server's asset as written by `app.test.runMain dosegram.app.moodBlobFixture`.
const states = asset as BlobAsset;
const state = (id: string): BlobState => findState(states, id)!;

describe('mood blob drawing', () => {
  it('maps the five stored steps to the drawn levels V1, V2, V4, V6 and V7 at the middle energy', () => {
    expect([1, 2, 3, 4, 5].map((step) => stateIdOf(step, undefined))).toEqual([
      'blob.v1.e3',
      'blob.v2.e3',
      'blob.v4.e3',
      'blob.v6.e3',
      'blob.v7.e3',
    ]);
    expect(stateIdOf(4, 5)).toBe('blob.v6.e5');
  });

  it('writes one cubic segment per six numbers of the outline and closes the path', () => {
    const outline = state('blob.v4.e3').outline;
    const path = outlinePath(outline);
    expect(path.startsWith('M')).toBe(true);
    expect(path.endsWith('Z')).toBe(true);
    expect(path.match(/C/g)).toHaveLength((outline.length - 2) / 6);
    expect(mouthPath([0, 0, 0.1, 0.2, 0.3, 0])).toBe('M0 0Q0.1 0.2 0.3 0');
  });

  it('blends the states the slider moves between, number by number, ending exactly on the target', () => {
    for (const energy of [1, 2, 3, 4, 5]) {
      const row = [1, 2, 3, 4, 5].map((step) => state(stateIdOf(step, energy)));
      row.slice(1).forEach((to, index) => expect(canBlend(row[index]!, to)).toBe(true));
    }
    const from = state('blob.v4.e3');
    const to = state('blob.v6.e3');
    expect(blend(from, to, 1).outline).toEqual(to.outline);
    expect(blend(from, to, 0).outline).toEqual(from.outline);
    const middle = blend(from, to, 0.5);
    expect(middle.outline[0]).toBeCloseTo((from.outline[0]! + to.outline[0]!) / 2, 6);
  });

  it('does not blend outlines of a different segment count', () => {
    expect(canBlend(state('blob.v4.e1'), state('blob.v4.e5'))).toBe(false);
  });

  it('mixes colours channel by channel', () => {
    expect(blendColour('#000000', '#ffffff', 0.5)).toBe('#808080');
    expect(blendColour('#102030', '#102030', 0.3)).toBe('#102030');
  });

  it('falls back to the "no entry" state for an unknown id', () => {
    expect(findState(states, 'blob.v9.e9')?.id).toBe('blob.none');
  });
});
