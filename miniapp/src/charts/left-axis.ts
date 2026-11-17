import type { Scene, SceneNode } from '../api/types';

export interface AxisLabel {
  readonly text: string;
  readonly top: string;
}

export interface LeftAxis {
  readonly share: string;
  readonly labels: readonly AxisLabel[];
  readonly nodes: readonly SceneNode[];
}

/**
 * Text the server placed left of the first hit. Those labels are the plot gutter.
 * A scene with no hits keeps every node in the SVG.
 */
export function leftAxis(scene: Scene): LeftAxis {
  const gutter = plotGutter(scene);
  if (gutter <= 0 || scene.width <= 0 || scene.height <= 0) {
    return { share: '0%', labels: [], nodes: scene.nodes };
  }
  const labels: AxisLabel[] = [];
  const nodes: SceneNode[] = [];
  for (const node of scene.nodes) {
    if (node.kind === 'text' && node.x < gutter) labels.push(axisLabel(node.text, node.y, scene.height));
    else nodes.push(node);
  }
  return { share: gutterShare(gutter, scene.width), labels, nodes };
}

function plotGutter(scene: Scene): number {
  let gutter = Number.POSITIVE_INFINITY;
  for (const node of scene.nodes) {
    if (node.kind === 'hit' && node.x < gutter) gutter = node.x;
  }
  return Number.isFinite(gutter) ? gutter : 0;
}

function axisLabel(text: string, y: number, height: number): AxisLabel {
  return { text, top: `${(y / height) * 100}%` };
}

/** 96 / 320 is 30%. Round so binary division still yields that exact percent. */
function gutterShare(gutter: number, width: number): string {
  const percent = Math.round(((gutter / width) * 100) * 1000) / 1000;
  return `${percent}%`;
}
