<script lang="ts">
  import type { TrackedDomain } from '../api/types';

  interface Props {
    /** One domain's strip, or `all` for a week of cells in every domain colour. */
    kind: TrackedDomain | 'all';
    /** Cells already filled; the rest are dotted outlines, which mean "not yet" (VISUALS 3.2). */
    filled?: number;
  }

  let { kind, filled = 2 }: Props = $props();

  const CELL = 24;
  const GAP = 8;
  const RADIUS = CELL * 0.28;
  const ALL: readonly TrackedDomain[] = ['medication', 'hydration', 'nutrition', 'mood', 'sleep'];

  const cells = $derived(
    kind === 'all'
      ? Array.from({ length: 7 }, (_, index) => ({ x: index * (CELL + GAP), tone: ALL[index] }))
      : Array.from({ length: 5 }, (_, index) => ({ x: 56 + index * (CELL + GAP), tone: index < filled ? kind : undefined })),
  );
  const width = $derived(kind === 'all' ? 7 * CELL + 6 * GAP : 56 + 5 * CELL + 4 * GAP);
</script>

<!-- Empty-state art in the identity of the image cards: rounded cells that are full or dotted
     ("not yet"), flat token colours, no gradient, no shadow (VISUALS 3.1, 3.2, IMG-18). It is
     decorative: the sentence beside it carries the meaning. -->
<svg class="art" viewBox="0 0 {width} 56" aria-hidden="true" focusable="false">
  {#if kind !== 'all'}
    <circle class="halo" data-tone={kind} cx="22" cy="28" r="22" />
    <g class="icon" data-tone={kind} transform="translate(10 16)">
      {#if kind === 'medication'}
        <rect x="4" y="4" width="16" height="16" rx="4.5" />
        <line x1="4" y1="12" x2="20" y2="12" />
      {:else if kind === 'hydration'}
        <path d="M12 3.5c3.3 4.1 6 7.4 6 10.6a6 6 0 0 1-12 0c0-3.2 2.7-6.5 6-10.6z" />
      {:else if kind === 'nutrition'}
        <circle cx="12" cy="12" r="8" />
        <path d="M8 12a4 4 0 0 0 8 0" />
      {:else if kind === 'mood'}
        <path
          d="M12 5.2c2.3 0 3.4 1.7 3.4 3.1 2-.3 3.9 1.2 3.5 3.5-.3 1.8-2 2.8-3.2 2.7.2 2.1-1.5 3.8-3.7 3.8s-3.9-1.7-3.7-3.8c-1.3.1-3-.9-3.2-2.7-.4-2.3 1.5-3.8 3.5-3.5 0-1.4 1.1-3.1 3.4-3.1z"
        />
      {:else}
        <path d="M15.6 4.4a8 8 0 1 0 4 11.4 6.6 6.6 0 0 1-4-11.4z" />
      {/if}
    </g>
  {/if}
  {#each cells as cell, index (index)}
    <rect
      class="cell"
      class:empty={cell.tone === undefined}
      data-tone={cell.tone}
      style:animation-delay="{index * 40}ms"
      x={cell.x + 1}
      y="17"
      width={CELL - 2}
      height={CELL - 2}
      rx={RADIUS}
    />
  {/each}
</svg>

<style>
  .art {
    display: block;
    inline-size: auto;
    block-size: 56px;
    max-inline-size: 100%;
  }

  .halo {
    fill-opacity: 0.12;
  }

  .icon {
    fill: none;
    stroke-width: 2;
    stroke-linecap: round;
    stroke-linejoin: round;
  }

  .cell {
    transform-box: fill-box;
    transform-origin: center;
    animation: settle var(--dg-motion-state) ease-out both;
  }

  .cell.empty {
    fill: none;
    stroke: var(--dg-text-muted);
    stroke-width: 1.5;
    stroke-dasharray: 1.5 4.5;
    stroke-linecap: round;
  }

  [data-tone='medication'] { fill: var(--dg-domain-medication); stroke: var(--dg-domain-medication); }
  [data-tone='hydration'] { fill: var(--dg-domain-hydration); stroke: var(--dg-domain-hydration); }
  [data-tone='nutrition'] { fill: var(--dg-domain-nutrition); stroke: var(--dg-domain-nutrition); }
  [data-tone='mood'] { fill: var(--dg-domain-mood); stroke: var(--dg-domain-mood); }
  [data-tone='sleep'] { fill: var(--dg-domain-sleep); stroke: var(--dg-domain-sleep); }

  .icon[data-tone] {
    fill: none;
  }

  rect.cell[data-tone] {
    stroke: none;
  }

  /* Motion answers the screen opening only, once, and is an instant cut under reduced motion,
     where the duration token is 0 (MA-DS-5). */
  @keyframes settle {
    from {
      opacity: 0;
      transform: scale(0.6);
    }
  }
</style>
