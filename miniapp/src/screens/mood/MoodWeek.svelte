<script lang="ts">
  import type { BlobAsset, MoodSummaryDay } from '../../api/types';
  import MoodBlob from '../../charts/MoodBlob.svelte';
  import { en } from '../../i18n/en';

  let { days, asset }: { days: readonly MoodSummaryDay[]; asset: BlobAsset } = $props();
</script>

<!-- One small blob per day for the daily mean, the dotted "no entry" blob for a day without a
     check-in (MA-MOOD-8). Each day is named in words, so shape and colour are never the only cue. -->
<section class="section" aria-labelledby="mood-week">
  <h2 class="section-title" id="mood-week">{en.mood.week}</h2>
  <ol class="section-body pad week">
    {#each days as day (day.date)}
      <li>
        <MoodBlob {asset} stateId={day.blobState} size={36} />
        <span class="muted" aria-hidden="true">{day.label}</span>
        <span class="visually-hidden">{day.name}</span>
      </li>
    {/each}
  </ol>
</section>

<style>
  .week {
    display: grid;
    grid-template-columns: repeat(7, 1fr);
    gap: var(--dg-space-1);
  }

  li {
    display: grid;
    justify-items: center;
    gap: var(--dg-space-1);
  }
</style>
