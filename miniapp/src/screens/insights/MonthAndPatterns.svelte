<script lang="ts">
  import type { InsightList, InsightMonth } from '../../api/types';
  import { useApp } from '../../app/context';
  import { en } from '../../i18n/en';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';

  // The month and the patterns in the server's words (VISUALS sections 6.6 and 8.5): no score, no
  // rating word, and a percentage only where the server printed one (MA-INS-2).
  const { api, tg } = useApp();
  const month = new Resource<InsightMonth>(() => api.insightMonth());
  const patterns = new Resource<InsightList>(() => api.insightList());

  $effect(() =>
    tg.onActivated(() => {
      void month.load();
      void patterns.load();
    }),
  );

  function show(target: string): void {
    tg.haptic('selection');
    void month.load(() => api.insightMonth(target));
  }
</script>

<div class="section" aria-busy={patterns.loading}>
  <h2 class="section-title" id="insights-patterns">{en.insights.patterns}</h2>
  <div class="section-body pad stack">
    <AsyncView resource={patterns} loadingLabel={en.insights.loadingPatterns} skeletonRows={2}>
      {#snippet children(list)}
        <p class="muted">{list.windowLabel}</p>
        {#if list.items.length === 0}
          <p>{en.insights.noPattern}</p>
        {/if}
        <ul class="plain stack">
          {#each list.items as item (item.id)}
            <li>
              <p>{item.text}</p>
              <details>
                <summary>{en.insights.howWorkedOut}</summary>
                {#each item.howWorkedOut as line, index (index)}<p class="muted">{line}</p>{/each}
              </details>
            </li>
          {/each}
        </ul>
        {#if list.close.length > 0}
          <h3 class="subtitle">{en.insights.close}</h3>
          <ul class="plain">
            {#each list.close as line, index (index)}<li class="muted">{line}</li>{/each}
          </ul>
        {/if}
        {#if list.notice}<p class="muted">{list.notice} {en.insights.compareInChat}</p>{/if}
      {/snippet}
    </AsyncView>
  </div>
</div>

<div class="section" aria-busy={month.loading}>
  <h2 class="section-title" id="insights-month">{en.insights.month}</h2>
  <div class="section-body pad stack">
    <AsyncView resource={month} loadingLabel={en.insights.loadingMonth} skeletonRows={3}>
      {#snippet children(data)}
        <div class="nav">
          <button type="button" class="link" onclick={() => show(data.previousMonth)}>{en.insights.previousMonth}</button>
          <p class="headline" aria-live="polite">{data.monthLabel}</p>
          {#if data.nextMonth}
            <button type="button" class="link" onclick={() => show(data.nextMonth!)}>{en.insights.nextMonth}</button>
          {/if}
        </div>
        <p class="numeric">{data.summary}</p>
        {#if data.notice}<p class="muted">{data.notice}</p>{/if}
        {#each data.domains as domain (domain.id)}
          <div class="domain">
            <h3 class="subtitle">{domain.title}</h3>
            {#if domain.gateText}
              <p class="muted">{domain.gateText}</p>
            {:else}
              {#each domain.lines as line, index (index)}<p class="numeric" class:muted={index > 0}>{line}</p>{/each}
            {/if}
          </div>
        {/each}
        {#if data.medications.length > 0}
          <h3 class="subtitle">{en.insights.byMedication}</h3>
          <ul class="plain stack" aria-label={en.insights.byMedication}>
            {#each data.medications as row (row.id)}
              <li>
                <p class="numeric">{row.line}</p>
                {#if row.resolved > 0}
                  <!-- The share as a bar beside its counts; the counts above are its text (VA-ME-02). -->
                  <div class="bar" aria-hidden="true">
                    <span style:width="{(100 * row.taken) / row.resolved}%"></span>
                  </div>
                {/if}
              </li>
            {/each}
          </ul>
        {/if}
      {/snippet}
    </AsyncView>
  </div>
</div>

<style>
  .stack {
    display: grid;
    gap: var(--dg-space-3);
  }

  .plain {
    list-style: none;
    margin: 0;
    padding: 0;
  }

  .nav {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--dg-space-3);
  }

  .headline {
    font-weight: 600;
  }

  .subtitle {
    font-size: var(--dg-text-body);
    font-weight: 600;
    margin: 0;
  }

  .domain {
    display: grid;
    gap: var(--dg-space-1);
  }

  .bar {
    height: 8px;
    border-radius: 4px;
    background: var(--dg-separator);
    overflow: hidden;
  }

  .bar span {
    display: block;
    height: 100%;
    background: var(--dg-domain-medication);
  }
</style>
