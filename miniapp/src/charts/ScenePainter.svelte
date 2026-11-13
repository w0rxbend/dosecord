<script lang="ts">
  import type { Scene } from '../api/types';

  interface Props {
    scene: Scene;
    label: string;
    /** The mark whose nodes are outlined as selected (MA-INS-6). */
    selected?: number;
  }

  let { scene, label, selected }: Props = $props();
</script>

<!-- The one scene painter (MINI_APP 5.6): it draws the primitives the server positioned and has
     no axis, scale or layout code of its own. Colours are token names resolved in CSS; nodes are
     created as DOM elements, never from markup strings (MA-SEC-1). -->
<svg viewBox="0 0 {scene.width} {scene.height}" role="img" aria-label={label}>
  {#each scene.nodes as node, index (index)}
    {#if node.kind === 'rect'}
      <rect
        x={node.x}
        y={node.y}
        width={node.width}
        height={node.height}
        rx={node.radius ?? 0}
        fill-opacity={node.opacity ?? 1}
        data-fill={node.fill}
        data-outlined={node.outlined ? 'true' : undefined}
        data-dashed={node.dashed ? 'true' : undefined}
        data-mark={node.mark}
        class:selected={node.mark !== undefined && node.mark === selected}
      />
    {:else if node.kind === 'line'}
      <line
        x1={node.x1}
        y1={node.y1}
        x2={node.x2}
        y2={node.y2}
        data-stroke={node.stroke}
        data-dashed={node.dashed ? 'true' : undefined}
      />
    {:else if node.kind === 'circle'}
      <circle
        cx={node.x}
        cy={node.y}
        r={node.r}
        data-fill={node.fill}
        data-mark={node.mark}
        class:selected={node.mark !== undefined && node.mark === selected}
      />
    {:else if node.kind === 'hit'}
      <rect
        x={node.x}
        y={node.y}
        width={node.width}
        height={node.height}
        class="hit"
        class:current={node.mark === selected}
        data-mark={node.mark}
      />
    {:else}
      <text x={node.x} y={node.y} text-anchor={node.anchor ?? 'start'} data-fill={node.fill}>{node.text}</text>
    {/if}
  {/each}
</svg>

<style>
  svg {
    display: block;
    inline-size: 100%;
    block-size: auto;
    overflow: visible;
  }

  text {
    font-size: 13px;
    font-variant-numeric: tabular-nums;
  }

  line {
    stroke-width: 1;
  }

  [data-fill='domain-medication'] { fill: var(--dg-domain-medication); }
  [data-fill='domain-hydration'] { fill: var(--dg-domain-hydration); }
  [data-fill='domain-mood'] { fill: var(--dg-domain-mood); }
  [data-fill='domain-sleep'] { fill: var(--dg-domain-sleep); }
  [data-fill='recorded'] { fill: var(--dg-recorded); }
  [data-fill='text'] { fill: var(--dg-text); }
  [data-fill='text-muted'] { fill: var(--dg-text-muted); }
  [data-fill='separator'] { fill: var(--dg-neutral-weak); }

  [data-stroke='text-muted'] { stroke: var(--dg-text-muted); }
  [data-stroke='separator'] { stroke: var(--dg-separator); }
  [data-stroke='text'] { stroke: var(--dg-text); }
  [data-stroke='domain-mood'] { stroke: var(--dg-domain-mood); stroke-width: 2; }
  [data-stroke='domain-hydration'] { stroke: var(--dg-domain-hydration); }

  /* An outline marks a partial or unanswered value, so shade is never the only cue. */
  [data-outlined='true'] {
    stroke: var(--dg-text-muted);
    stroke-width: 1;
  }

  [data-dashed='true'] {
    stroke-dasharray: 4 4;
  }

  /* A tap target over one day: invisible until it is the selected one. */
  .hit {
    fill: transparent;
    cursor: pointer;
  }

  .hit.current {
    fill: var(--dg-text);
    fill-opacity: 0.06;
  }

  .selected {
    stroke: var(--dg-focus);
    stroke-width: 2;
  }
</style>
