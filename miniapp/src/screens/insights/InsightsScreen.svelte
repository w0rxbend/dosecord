<script lang="ts">
  import type { InsightRange, InsightsOverview } from '../../api/types';
  import { useApp } from '../../app/context';
  import ChartFigure from '../../charts/ChartFigure.svelte';
  import Vessel from '../../charts/Vessel.svelte';
  import { en } from '../../i18n/en';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import LinkRow from '../../ui/LinkRow.svelte';
  import Screen from '../../ui/Screen.svelte';

  /** Offered until the first answer names the periods the backend computes. */
  const FIRST_RANGES: readonly InsightRange[] = [7, 30];

  const { api, tg } = useApp();
  let range = $state<InsightRange>(30);
  const overview = new Resource<InsightsOverview>(() => api.insightsOverview(30));
  const ranges = $derived(overview.data?.ranges ?? FIRST_RANGES);

  $effect(() => tg.onActivated(() => void overview.load()));

  function changeRange(): void {
    tg.haptic('selection');
    const chosen = range;
    void overview.load(() => api.insightsOverview(chosen));
  }
</script>

<Screen title={en.insights.title} subtitle={overview.data?.rangeLabel}>
  <fieldset class="section">
    <legend class="visually-hidden">{en.insights.range}</legend>
    <div class="chips">
      {#each ranges as option (option)}
        <label class="chip">
          <input type="radio" name="insights-range" value={option} bind:group={range} onchange={changeRange} />
          <span class="numeric">{en.insights.days(option)}</span>
        </label>
      {/each}
    </div>
  </fieldset>

  <AsyncView resource={overview} loadingLabel={en.insights.loading} skeletonRows={4}>
    {#snippet children(data)}
      {#if data.notice}<p class="muted">{data.notice}</p>{/if}
      <div class="blocks" aria-busy={overview.loading}>
        {#each data.blocks as block (block.id)}
          <section class="section" aria-labelledby="insight-{block.id}">
            <h2 class="section-title" id="insight-{block.id}">{block.title}</h2>
            <div class="section-body pad block">
              <!-- Counts and durations in the server's words: no score, no rating (MA-INS-2). -->
              {#if block.noDataText}
                <p>{block.noDataText}</p>
              {:else}
                <div class="lead">
                  {#if block.vessel}<Vessel {...block.vessel} />{/if}
                  <div>
                    {#each block.lines as line, index (index)}
                      <p class="numeric" class:headline={index === 0} class:muted={index > 0}>{line}</p>
                    {/each}
                    {#if block.vessel}<p class="numeric">{block.vessel.label}</p>{/if}
                  </div>
                </div>
                {#each block.charts as chart, index (chart.id)}
                  <ChartFigure {chart} id="chart-{block.id}-{index}" />
                {/each}
              {/if}
            </div>
          </section>
        {/each}
      </div>
      <div class="section section-body">
        <LinkRow path="/insights/history" label={en.insights.history} hint={en.insights.historyHint} />
      </div>
    {/snippet}
  </AsyncView>
</Screen>

<style>
  /* From 720 px the blocks sit in two columns in reading order (MA-INS-9). */
  @media (min-width: 720px) {
    .blocks {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: var(--dg-space-4);
      align-items: start;
    }
  }

  .block {
    display: grid;
    gap: var(--dg-space-5);
  }

  .lead {
    display: flex;
    gap: var(--dg-space-4);
    align-items: center;
  }

  .headline {
    font-size: var(--dg-text-title);
    font-weight: 600;
    line-height: 1.2;
  }
</style>
