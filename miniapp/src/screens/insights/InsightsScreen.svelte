<script lang="ts">
  import { tick } from 'svelte';
  import type { InsightBlock, InsightRange, InsightsOverview } from '../../api/types';
  import { useApp } from '../../app/context';
  import { t as en } from '../../i18n/index.svelte';
  import { isReducedMotion } from '../../state/motion';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import EmptyState from '../../ui/EmptyState.svelte';
  import LinkRow from '../../ui/LinkRow.svelte';
  import Screen from '../../ui/Screen.svelte';
  import DomainBlock from './DomainBlock.svelte';
  import GlanceTile from './GlanceTile.svelte';
  import MonthAndPatterns from './MonthAndPatterns.svelte';

  /** Offered until the first answer names the periods the backend computes. */
  const FIRST_RANGES: readonly InsightRange[] = [7, 30];

  const { api, tg } = useApp();
  let range = $state<InsightRange>(30);
  const overview = new Resource<InsightsOverview>(() => api.insightsOverview(30));
  const ranges = $derived(overview.data?.ranges ?? FIRST_RANGES);

  $effect(() => tg.onActivated(() => void overview.load()));

  // The chosen period applies to every block (MA-INS-1, VA-MA-12).
  function changeRange(): void {
    tg.haptic('selection');
    const chosen = range;
    void overview.load(() => api.insightsOverview(chosen));
  }

  /** A tile leads to its block: the block's heading takes the focus and comes into view. */
  async function open(block: InsightBlock): Promise<void> {
    await tick();
    const heading = document.getElementById(`insight-${block.id}`);
    heading?.focus({ preventScroll: true });
    heading?.scrollIntoView?.({ block: 'start', behavior: isReducedMotion(tg) ? 'auto' : 'smooth' });
  }

  const withData = (data: InsightsOverview): boolean => data.blocks.some((block) => !block.noDataText);
</script>

<Screen title={en.insights.title} subtitle={overview.data?.rangeLabel}>
  <fieldset class="section">
    <legend class="visually-hidden">{en.insights.range}</legend>
    <div class="segmented">
      {#each ranges as option (option)}
        <label>
          <input type="radio" name="insights-range" value={option} bind:group={range} onchange={changeRange} />
          <span class="numeric">{en.insights.days(option)}</span>
        </label>
      {/each}
    </div>
  </fieldset>

  <AsyncView resource={overview} loadingLabel={en.insights.loading} skeletonRows={4}>
    {#snippet children(data)}
      {#if data.notice}<p class="muted notice">{data.notice}</p>{/if}
      {#if data.blocks.length > 0 && !withData(data)}
        <div class="section"><EmptyState message={en.insights.empty} art="all" /></div>
      {/if}
      {#if data.blocks.length > 1}
        <nav class="section glance" aria-label={en.insights.glance}>
          {#each data.blocks as block (block.id)}
            <GlanceTile {block} onOpen={(chosen) => void open(chosen)} />
          {/each}
        </nav>
      {/if}
      <div class="blocks" aria-busy={overview.loading}>
        {#each data.blocks as block (block.id)}
          <DomainBlock {block} />
        {/each}
      </div>
      {#if data.blocks.length > 0}<MonthAndPatterns />{/if}
      <div class="section section-body">
        <LinkRow path="/insights/history" label={en.insights.history} hint={en.insights.historyHint} />
        <LinkRow path="/insights/reports" label={en.reports.title} hint={en.reports.hint} />
      </div>
    {/snippet}
  </AsyncView>
</Screen>

<style>
  .notice {
    margin-block-end: var(--dg-space-4);
  }

  /* Tiles in two columns; one column on the narrowest phones (MA-LAYOUT-3). */
  .glance {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--dg-space-3);
  }

  /* An odd last tile takes the whole row instead of leaving a hole. */
  .glance > :global(:last-child:nth-child(odd)) {
    grid-column: 1 / -1;
  }

  @media (max-width: 339px) {
    .glance {
      grid-template-columns: 1fr;
    }
  }

  /* From 720 px the blocks sit in two columns in reading order (MA-INS-9). */
  @media (min-width: 720px) {
    .blocks {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: var(--dg-space-4);
      align-items: start;
    }

    .glance {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }
  }
</style>
