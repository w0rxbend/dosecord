<script lang="ts">
  import type { WaterEntry } from '../../api/types';
  import { t as en } from '../../i18n/index.svelte';
  import { isPending } from './water';

  let { entries, onRemove }: { entries: readonly WaterEntry[]; onRemove: (entry: WaterEntry) => void } = $props();
</script>

<section class="section" aria-labelledby="water-today">
  <h2 class="section-title" id="water-today">{en.water.today}</h2>
  {#if entries.length === 0}
    <p class="section-body pad">{en.water.nothing}</p>
  {:else}
    <ul class="section-body">
      {#each entries as entry (entry.id)}
        <li class="row">
          <span class="time numeric">{entry.time}</span>
          <span class="amount numeric">{entry.text}</span>
          {#if !isPending(entry)}
            <button type="button" class="btn" aria-label={en.water.remove(entry.text, entry.time)} onclick={() => onRemove(entry)}>
              {en.water.removeShort}
            </button>
          {/if}
        </li>
      {/each}
    </ul>
  {/if}
</section>

<style>
  .row {
    display: flex;
    align-items: center;
    gap: var(--dg-space-4);
    min-block-size: var(--dg-target);
    padding-inline: var(--dg-space-4) var(--dg-space-2);
  }

  .time {
    color: var(--dg-text-muted);
  }

  .amount {
    flex: 1;
  }
</style>
