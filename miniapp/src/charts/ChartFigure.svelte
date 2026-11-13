<script lang="ts">
  import type { Chart } from '../api/types';
  import { en } from '../i18n/en';
  import ScenePainter from './ScenePainter.svelte';

  let { chart, id }: { chart: Chart; id: string } = $props();
  let tableOpen = $state(false);
</script>

<!-- Every chart has a title, a one-sentence summary that is also its accessible name, and the
     same data as a table (MA-INS-5, MA-A11Y-7). -->
<figure>
  <figcaption>{chart.title}</figcaption>
  <ScenePainter scene={chart.scene} label={chart.summary} />
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
          {#each chart.table.rows as row (row[0])}
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
