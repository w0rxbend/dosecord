<script lang="ts">
  import type { SchedulePreview } from '../../api/types';
  import { en } from '../../i18n/en';

  interface Props {
    preview: SchedulePreview | null;
    /** True while a newer preview is on its way; the previous one stays visible. */
    stale: boolean;
    problem: string | null;
  }

  let { preview, stale, problem }: Props = $props();
</script>

<!-- The next seven days come from the server, which owns the schedule and clock-change rules.
     The client shows its sentences and computes none (MINI_APP 7.5 behaviour 2 to 4). -->
<section class="section" aria-labelledby="preview-title">
  <h2 class="section-title" id="preview-title">
    {preview ? en.schedule.preview(preview.zoneLabel) : en.schedule.previewTitle}
  </h2>
  <div class="section-body" class:stale aria-busy={stale}>
    {#if problem}
      <p class="pad" role="alert">{problem}</p>
    {:else if !preview}
      <p class="pad muted">{en.schedule.previewInvalid}</p>
    {:else if preview.days.length === 0}
      <p class="pad">{en.schedule.asNeeded}</p>
    {:else}
      {#each preview.days as day (day.date)}
        <div class="pad day">
          <h3 class="numeric">{day.label}</h3>
          <ul>
            {#each day.occurrences as occurrence (occurrence.time)}
              <li class="numeric">
                {occurrence.time} <span class="muted">{occurrence.doseText}</span>
                {#if occurrence.note}<span class="muted">({occurrence.note})</span>{/if}
              </li>
            {/each}
          </ul>
        </div>
      {/each}
      {#if preview.moreCount > 0}<p class="pad muted numeric">{en.schedule.more(preview.moreCount)}</p>{/if}
    {/if}
  </div>
  <p class="visually-hidden" role="status">{stale ? en.schedule.previewLoading : ''}</p>
</section>

<style>
  .stale {
    opacity: 0.5;
  }

  .day {
    display: grid;
    grid-template-columns: 7rem 1fr;
    gap: var(--dg-space-2);
  }

  h3 {
    font-size: var(--dg-text-body);
    font-weight: 600;
  }

  ul {
    display: flex;
    flex-wrap: wrap;
    gap: var(--dg-space-1) var(--dg-space-4);
  }
</style>
