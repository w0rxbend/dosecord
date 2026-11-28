<script lang="ts">
  import type { InsightRhythmArea } from '../../api/types';

  // One area's 24 hours as bars from the band's floor on the area's own scale, 00:00 on the left
  // (VISUALS IMG-28); an hour without a record has no bar. They repeat the table, which is the text
  // equivalent, so they are hidden from assistive technology.
  interface Props {
    area: InsightRhythmArea;
    hours: readonly number[];
  }

  let { area, hours }: Props = $props();

  const largest = $derived(Math.max(1, ...hours));
</script>

<div class="band" aria-hidden="true">
  {#each hours as count, hour (hour)}
    <span class="slot">
      {#if count > 0}
        <span class="bar" style:block-size="{Math.max(12, (count / largest) * 100)}%" style:background="var(--dg-domain-{area.id})"></span>
      {/if}
    </span>
  {/each}
</div>
<div class="axis" aria-hidden="true"><span>00:00</span><span>06:00</span><span>12:00</span><span>18:00</span><span>24:00</span></div>

<style>
  .band {
    display: grid;
    grid-template-columns: repeat(24, minmax(0, 1fr));
    gap: 2px;
    block-size: 48px;
    padding: 2px;
    border-radius: 6px;
    background: var(--dg-separator);
  }

  .slot {
    display: flex;
    align-items: flex-end;
  }

  .bar {
    inline-size: 100%;
    border-radius: 2px;
  }

  .axis {
    display: flex;
    justify-content: space-between;
    font-size: var(--dg-text-secondary);
    color: var(--dg-text-muted);
  }
</style>
