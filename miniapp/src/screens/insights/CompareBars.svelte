<script lang="ts">
  import type { InsightCompareArea, InsightCompareCell } from '../../api/types';

  // The two periods of one area as paired bars on the area's own scale, mood as a dot on its 1 to 5
  // track, a dotted track for a period below its gate (VISUALS VA-PR-03). They repeat the table, which
  // is the text equivalent, so they are hidden from assistive technology.
  interface Props {
    area: InsightCompareArea;
    labels: readonly [string, string];
  }

  let { area, labels }: Props = $props();

  /** The share of the row's larger figure a bar fills; mood sits on its 1 to 5 track. */
  function share(cell: InsightCompareCell): number {
    if (cell.value === undefined) return 0;
    if (area.id === 'mood') return ((Math.min(5, Math.max(1, cell.value)) - 1) / 4) * 100;
    const largest = Math.max(area.earlier.value ?? 0, area.later.value ?? 0, 1);
    return Math.max(2, (cell.value / largest) * 100);
  }
</script>

<div class="pairs" aria-hidden="true">
  <span class="name">{area.title}</span>
  {#each [area.earlier, area.later] as cell, index (index)}
    <div class="bar-row">
      <span class="bar-label">{labels[index]}</span>
      <span class="track" class:empty={cell.value === undefined}>
        {#if cell.value !== undefined && area.id === 'mood'}
          <span class="dot" style:inset-inline-start="calc({share(cell)}% - 6px)" style:background="var(--dg-domain-{area.id})"></span>
        {:else if cell.value !== undefined}
          <span class="fill" style:inline-size="{share(cell)}%" style:background="var(--dg-domain-{area.id})"></span>
        {/if}
      </span>
    </div>
  {/each}
</div>

<style>
  .pairs {
    display: grid;
    gap: var(--dg-space-1);
  }

  .name {
    font-size: var(--dg-text-secondary);
    color: var(--dg-text-muted);
  }

  .bar-row {
    display: grid;
    grid-template-columns: 7rem 1fr;
    align-items: center;
    gap: var(--dg-space-2);
    font-size: var(--dg-text-secondary);
  }

  .track {
    position: relative;
    block-size: 12px;
    border-radius: 6px;
    background: var(--dg-separator);
    overflow: hidden;
  }

  .track.empty {
    background: none;
    border: 2px dotted var(--dg-text-muted);
  }

  .fill {
    display: block;
    block-size: 100%;
    border-radius: 6px;
  }

  .dot {
    position: absolute;
    inset-block-start: 0;
    inline-size: 12px;
    block-size: 12px;
    border-radius: 50%;
  }
</style>
