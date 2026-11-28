<script lang="ts">
  import type { InsightYear } from '../../api/types';
  import { useApp } from '../../app/context';
  import ChartFigure from '../../charts/ChartFigure.svelte';
  import { t as en } from '../../i18n/index.svelte';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';

  // The year of /stats year in the server's words (MINI_APP 7.11, the Year tab; supervisor decision,
  // 2026-11-28): per area a table of months, which is the text equivalent and always shown, and for
  // water, food, sleep and mood the weeks as a heat strip. Medication has none (PRODUCT_SPEC R-GAM-2).
  const { api, tg } = useApp();
  const year = new Resource<InsightYear>(() => api.insightYear());

  $effect(() => tg.onActivated(() => void year.load()));

  function show(target: number): void {
    tg.haptic('selection');
    void year.load(() => api.insightYear(target));
  }
</script>

<div class="year" aria-busy={year.loading}>
  <AsyncView resource={year} loadingLabel={en.insights.loadingYear} skeletonRows={4}>
    {#snippet children(data)}
      <section class="section" aria-labelledby="insights-year">
        <h2 class="section-title" id="insights-year">{en.insights.yearTab}</h2>
        <div class="section-body pad stack">
          <div class="nav">
            {#if data.previousYear}
              <button type="button" class="link" onclick={() => show(data.previousYear!)}>{en.insights.previousYear}</button>
            {/if}
            <p class="headline numeric" aria-live="polite">{data.yearLabel}</p>
            {#if data.nextYear}
              <button type="button" class="link" onclick={() => show(data.nextYear!)}>{en.insights.nextYear}</button>
            {/if}
          </div>
          <p class="numeric">{data.summary}</p>
          {#if data.outageNote}<p class="muted">{data.outageNote}</p>{/if}
          {#if data.notice}<p class="muted">{data.notice}</p>{/if}
          {#if data.gateNote}<p class="muted">{data.gateNote}</p>{/if}
        </div>
      </section>

      {#each data.areas as area (area.id)}
        <section class="section" aria-labelledby="year-{area.id}">
          <h2 class="section-title" id="year-{area.id}">{area.title}</h2>
          <div class="section-body pad stack">
            {#if area.total}<p class="numeric">{area.total}</p>{/if}
            {#if area.heat}<ChartFigure chart={area.heat} id="year-heat-{area.id}" />{/if}
            <div class="scroll">
              <table class="numeric">
                <caption>{area.title}: {en.insights.yearMonths}</caption>
                <thead>
                  <tr>
                    <th scope="col">{en.insights.yearMonth}</th>
                    <th scope="col">{en.insights.yearDays}</th>
                    <th scope="col">{en.insights.yearFigure}</th>
                  </tr>
                </thead>
                <tbody>
                  {#each area.months as month (month.month)}
                    <tr>
                      <th scope="row">{month.label}</th>
                      <td>{month.recordedDays}</td>
                      <td>{month.text}</td>
                    </tr>
                  {/each}
                </tbody>
              </table>
            </div>
          </div>
        </section>
      {/each}

      {#if data.lines.length > 0}
        <details class="section section-body pad">
          <summary>{en.insights.yearText}</summary>
          {#each data.lines as line, index (index)}<p class="numeric">{line}</p>{/each}
        </details>
      {/if}
    {/snippet}
  </AsyncView>
</div>

<style>
  .stack {
    display: grid;
    gap: var(--dg-space-3);
  }

  .nav {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: var(--dg-space-3);
  }

  .headline {
    font-weight: 600;
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
    padding: var(--dg-space-2);
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
