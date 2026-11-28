<script lang="ts">
  import type { CompareChoice, InsightCompare } from '../../api/types';
  import { useApp } from '../../app/context';
  import { t as en } from '../../i18n/index.svelte';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import CompareBars from './CompareBars.svelte';

  // Two periods of /stats compare in the server's words (MINI_APP 7.11, the Compare view; supervisor
  // decision, 2026-11-28): the table of both periods per area is the text equivalent and always shown;
  // the paired bars beside it repeat the table for the eye and are hidden from assistive technology.
  const { api, tg } = useApp();
  const comparison = new Resource<InsightCompare>(() => api.insightCompare());
  let kind = $state<CompareChoice['kind']>('weeks');
  let first = $state('');
  let second = $state('');

  $effect(() => tg.onActivated(() => void comparison.load()));

  function load(choice: CompareChoice): void {
    tg.haptic('selection');
    void comparison.load(() => api.insightCompare(choice));
  }

  function changeKind(): void {
    if (kind === 'pair') {
      const months = comparison.data?.months ?? [];
      first = first || (months[1]?.month ?? '');
      second = second || (months[0]?.month ?? '');
      return;
    }
    load({ kind });
  }

  function comparePair(event: SubmitEvent): void {
    event.preventDefault();
    if (first && second && first !== second) load({ kind: 'pair', first, second });
  }
</script>

<div class="compare" aria-busy={comparison.loading}>
  <fieldset class="section">
    <legend class="visually-hidden">{en.insights.compareWhat}</legend>
    <div class="segmented">
      <label>
        <input type="radio" name="compare-kind" value="weeks" bind:group={kind} onchange={changeKind} />
        <span>{en.insights.compareWeeks}</span>
      </label>
      <label>
        <input type="radio" name="compare-kind" value="months" bind:group={kind} onchange={changeKind} />
        <span>{en.insights.compareMonths}</span>
      </label>
      <label>
        <input type="radio" name="compare-kind" value="pair" bind:group={kind} onchange={changeKind} />
        <span>{en.insights.comparePair}</span>
      </label>
    </div>
  </fieldset>

  <AsyncView resource={comparison} loadingLabel={en.insights.loadingCompare} skeletonRows={4}>
    {#snippet children(data)}
      {#if kind === 'pair'}
        <form class="section section-body pad pair" onsubmit={comparePair}>
          <label>
            <span>{en.insights.compareFirst}</span>
            <select bind:value={first}>
              {#each data.months as month (month.month)}<option value={month.month}>{month.label}</option>{/each}
            </select>
          </label>
          <label>
            <span>{en.insights.compareSecond}</span>
            <select bind:value={second}>
              {#each data.months as month (month.month)}<option value={month.month}>{month.label}</option>{/each}
            </select>
          </label>
          <button type="submit" class="btn btn-tinted" disabled={!first || !second || first === second}>
            {en.insights.compareShow}
          </button>
        </form>
      {/if}

      <section class="section" aria-labelledby="insights-compare">
        <h2 class="section-title" id="insights-compare">{en.insights.compareTab}</h2>
        <div class="section-body pad stack" aria-live="polite">
          <p class="numeric">{data.earlier.summary}</p>
          <p class="numeric">{data.later.summary}</p>
          {#if data.notice}<p class="muted">{data.notice}</p>{/if}
          {#if data.gateNote}<p class="muted">{data.gateNote}</p>{/if}
          {#if data.areas.length > 0}<p class="muted">{data.differenceNote}</p>{/if}
        </div>
      </section>

      {#if data.areas.length > 0}
        <section class="section" aria-labelledby="compare-table">
          <h2 class="section-title" id="compare-table">{en.insights.compareTable}</h2>
          <div class="section-body pad stack">
            {#each data.areas as area (area.id)}
              <CompareBars {area} labels={[data.earlier.label, data.later.label]} />
            {/each}
            <div class="scroll">
              <table class="numeric">
                <caption>{en.insights.compareTable}</caption>
                <thead>
                  <tr>
                    <th scope="col">{en.insights.compareArea}</th>
                    <th scope="col">{data.earlier.label}</th>
                    <th scope="col">{data.later.label}</th>
                    <th scope="col">{en.insights.compareDifference}</th>
                  </tr>
                </thead>
                <tbody>
                  {#each data.areas as area (area.id)}
                    <tr>
                      <th scope="row">{area.title}</th>
                      <td>{area.earlier.text}</td>
                      <td>{area.later.text}</td>
                      <td>{area.difference ?? '—'}</td>
                    </tr>
                  {/each}
                </tbody>
              </table>
            </div>
          </div>
        </section>
      {/if}

      {#if data.lines.length > 0}
        <details class="section section-body pad">
          <summary>{en.insights.compareText}</summary>
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

  .pair {
    display: grid;
    gap: var(--dg-space-3);
  }

  .pair label {
    display: grid;
    gap: var(--dg-space-1);
  }

  .pair select {
    min-block-size: var(--dg-target);
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
