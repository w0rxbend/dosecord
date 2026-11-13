<script lang="ts">
  import type { Scene } from '../api/types';

  let { scene, label }: { scene: Scene; label: string } = $props();
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
  [data-fill='recorded'] { fill: var(--dg-recorded); }
  [data-fill='text'] { fill: var(--dg-text); }
  [data-fill='text-muted'] { fill: var(--dg-text-muted); }
  [data-fill='separator'] { fill: var(--dg-neutral-weak); }

  [data-stroke='text-muted'] { stroke: var(--dg-text-muted); }
  [data-stroke='separator'] { stroke: var(--dg-separator); }
  [data-stroke='text'] { stroke: var(--dg-text); }

  /* An outline marks a partial or unanswered value, so shade is never the only cue. */
  [data-outlined='true'] {
    stroke: var(--dg-text-muted);
    stroke-width: 1;
  }

  [data-dashed='true'] {
    stroke-dasharray: 4 4;
  }
</style>
