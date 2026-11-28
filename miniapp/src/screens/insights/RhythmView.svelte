<script lang="ts">
  import type { InsightRhythm } from '../../api/types';
  import { useApp } from '../../app/context';
  import { rhythmWords } from '../../i18n/rhythm';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import RhythmBand from './RhythmBand.svelte';

  // The rhythm of the day of /stats rhythm in the server's words (MINI_APP 7.11, the Rhythm view;
  // supervisor decision, 2026-11-28): the table of records by hour is the text equivalent and always
  // shown; the bands above it repeat the table for the eye and are hidden from assistive technology.
  const { api, tg } = useApp();
  const rhythm = new Resource<InsightRhythm>(() => api.insightRhythm());
  const words = $derived(rhythmWords());
  const hour = (h: number): string => `${String(h).padStart(2, '0')}:00`;
  const shown = (data: InsightRhythm) => data.areas.filter((area) => area.hours !== undefined);

  $effect(() => tg.onActivated(() => void rhythm.load()));
</script>

<AsyncView resource={rhythm} loadingLabel={words.loading} skeletonRows={4}>
  {#snippet children(data)}
    <section class="section" aria-labelledby="insights-rhythm">
      <h2 class="section-title" id="insights-rhythm">{words.tab}</h2>
      <div class="section-body pad stack">
        <p class="numeric">{data.summary}</p>
        {#if data.notice}<p class="muted">{data.notice}</p>{/if}
        {#if data.gateNote}<p class="muted">{data.gateNote}</p>{/if}
        {#if data.areas.length > 0}<p class="muted">{data.clockNote}</p>{/if}
        {#each data.areas as area (area.id)}
          <div class="area">
            <p class="name"><span>{area.title}</span><span class="numeric muted">{area.days}</span></p>
            {#if area.hours}
              <RhythmBand {area} hours={area.hours} />
              <p class="numeric">{area.summary}</p>
            {:else}
              <p class="numeric muted">{area.text}</p>
            {/if}
          </div>
        {/each}
      </div>
    </section>

    {#if shown(data).length > 0}
      <section class="section" aria-labelledby="rhythm-table">
        <h2 class="section-title" id="rhythm-table">{words.table}</h2>
        <div class="section-body pad scroll">
          <table class="numeric">
            <caption>{words.table}</caption>
            <thead>
              <tr>
                <th scope="col">{words.hour}</th>
                {#each shown(data) as area (area.id)}<th scope="col">{area.counts}</th>{/each}
              </tr>
            </thead>
            <tbody>
              {#each { length: 24 } as _, h (h)}
                <tr>
                  <th scope="row">{hour(h)}–{hour((h + 1) % 24)}</th>
                  {#each shown(data) as area (area.id)}<td>{area.hours?.[h] ?? 0}</td>{/each}
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      </section>
    {/if}

    {#if data.lines.length > 0}
      <details class="section section-body pad">
        <summary>{words.text}</summary>
        {#each data.lines as line, index (index)}<p class="numeric">{line}</p>{/each}
      </details>
    {/if}
  {/snippet}
</AsyncView>

<style>
  .stack,
  .area {
    display: grid;
    gap: var(--dg-space-2);
  }

  .stack {
    gap: var(--dg-space-3);
  }

  .name {
    display: flex;
    justify-content: space-between;
    gap: var(--dg-space-2);
  }

  .scroll {
    overflow-x: auto;
  }

  table {
    inline-size: 100%;
    border-collapse: collapse;
    font-size: var(--dg-text-secondary);
  }

  caption {
    text-align: start;
    color: var(--dg-text-muted);
    padding-block-end: var(--dg-space-2);
  }

  th,
  td {
    padding: var(--dg-space-1) var(--dg-space-2);
    border-block-end: 1px solid var(--dg-separator);
    text-align: start;
    font-weight: 400;
  }

  thead th {
    font-weight: 600;
  }

  summary {
    min-block-size: var(--dg-target);
    display: flex;
    align-items: center;
    cursor: pointer;
  }
</style>
