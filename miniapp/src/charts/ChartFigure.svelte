<script lang="ts">
  import type { Chart } from '../api/types';
  import { useApp } from '../app/context';
  import { historyDayPath } from '../app/routes';
  import { en } from '../i18n/en';
  import ScenePainter from './ScenePainter.svelte';

  let { chart, id }: { chart: Chart; id: string } = $props();
  const { router } = useApp();
  let tableOpen = $state(false);
  // The last mark is the most recent day: it is the one shown until the person picks another.
  let selected = $state<number | undefined>(undefined);
  const current = $derived(selected ?? chart.marks.length - 1);
  const caption = $derived(chart.marks[current]);
  // A chart of days names the day of each mark: the picked day opens in Day detail (S-13).
  const day = $derived(chart.markDates?.[current]);

  function openDay(): void {
    if (day) void router.navigate(historyDayPath(day));
  }

  // The first tap on a mark shows its value; a second tap on the same mark opens its day.
  function pick(event: MouseEvent): void {
    const mark = (event.target as Element | null)?.getAttribute('data-mark');
    if (mark === null || mark === undefined || mark === '') return;
    const index = Number(mark);
    if (selected === index && day) openDay();
    else selected = index;
  }

  // The values are reachable by keyboard: the arrow keys move between marks (MA-INS-6).
  function step(event: KeyboardEvent): void {
    const last = chart.marks.length - 1;
    if (event.key === 'Enter' && day) {
      event.preventDefault();
      openDay();
      return;
    }
    const moves: Record<string, number> = { ArrowLeft: current - 1, ArrowRight: current + 1, Home: 0, End: last };
    const next = moves[event.key];
    if (next === undefined) return;
    event.preventDefault();
    selected = Math.max(0, Math.min(last, next));
  }
</script>

<!-- Every chart has a title, a one-sentence summary that is also its accessible name, the same
     data as a table (MA-INS-5, MA-A11Y-7), and the exact value of a picked mark in a caption that
     stays under the chart instead of a hover tooltip (MA-INS-6). -->
<figure>
  <figcaption>{chart.title}</figcaption>
  {#if chart.marks.length > 0}
    <div
      class="plot"
      role="slider"
      tabindex="0"
      aria-label={en.insights.marksOf(chart.title)}
      aria-valuemin={0}
      aria-valuemax={chart.marks.length - 1}
      aria-valuenow={current}
      aria-valuetext={caption}
      onclick={pick}
      onkeydown={step}
    >
      <ScenePainter scene={chart.scene} label={chart.summary} selected={current} />
    </div>
    <div class="picked">
      <p class="caption numeric" aria-live="polite">{caption}</p>
      {#if day}
        <a class="open" href={router.hrefFor(historyDayPath(day))} onclick={(event) => { event.preventDefault(); openDay(); }}>
          {en.insights.openDay}
        </a>
      {/if}
    </div>
  {:else}
    <ScenePainter scene={chart.scene} label={chart.summary} />
  {/if}
  <p class="muted numeric">{chart.summary}</p>
  <button
    type="button"
    class="btn"
    aria-expanded={tableOpen}
    aria-controls="{id}-table"
    onclick={() => (tableOpen = !tableOpen)}
  >
    {tableOpen ? en.common.hideTable : en.common.showTable}
  </button>
  {#if tableOpen}
    <div class="scroll" id="{id}-table">
      <table class="numeric">
        <caption>{chart.table.caption}</caption>
        <thead>
          <tr>
            {#each chart.table.columns as column (column)}<th scope="col">{column}</th>{/each}
          </tr>
        </thead>
        <tbody>
          {#each chart.table.rows as row, rowIndex (rowIndex)}
            <tr>
              {#each row as cell, index (index)}
                {#if index === 0}<th scope="row">{cell}</th>{:else}<td>{cell}</td>{/if}
              {/each}
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
  {/if}
</figure>

<style>
  figure {
    display: grid;
    gap: var(--dg-space-2);
    margin: 0;
  }

  figcaption {
    font-weight: 600;
  }

  .plot {
    border-radius: var(--dg-radius-control);
    touch-action: manipulation;
  }

  .picked {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: var(--dg-space-2);
    min-block-size: var(--dg-target);
  }

  .caption {
    min-block-size: 1.4em;
    font-weight: 600;
  }

  .open {
    display: inline-flex;
    align-items: center;
    min-block-size: var(--dg-target);
    color: var(--dg-accent-text);
    font-weight: 600;
    text-decoration: none;
  }

  .btn {
    justify-self: start;
    padding-inline: 0;
  }

  .scroll {
    max-block-size: 16rem;
    overflow: auto;
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
</style>
