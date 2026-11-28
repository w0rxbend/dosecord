<script lang="ts">
  import { onDestroy, tick } from 'svelte';
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
  import ShareCards from './ShareCards.svelte';
  import CompareView from './CompareView.svelte';
  import RhythmView from './RhythmView.svelte';
  import YearView from './YearView.svelte';
  import { rhythmWords } from '../../i18n/rhythm';
  import { COMPARE, DEFAULT_RANGE, RANGE_KEY, RHYTHM, YEAR, overviewDays, shownOf, type Shown } from './range-preference';

  /** Offered until the first answer names the periods the backend computes. */
  const FIRST_RANGES: readonly InsightRange[] = [7, 30];
  /** The first overview answer paints these. Later blocks wait until they are near (MA-INS-8). */
  const EAGER_BLOCKS = 2;
  const NEAR_VIEWPORT_PX = 200;

  const { api, tg } = useApp();
  // Compare and Rhythm are not remembered: the stored key keeps a period or the Year tab only (MINI_APP 6.6).
  let range = $state<Shown | typeof COMPARE | typeof RHYTHM>(DEFAULT_RANGE);
  const tabs = $derived([[YEAR, en.insights.yearTab], [COMPARE, en.insights.compareTab], [RHYTHM, rhythmWords().tab]]);
  let revealed = $state<string[]>([]);
  // The first read waits for the stored choice (at most 500 ms, MA-STORE-2), so it asks for that period only.
  const overview = new Resource<InsightsOverview>(async () => {
    const restored = shownOf(await tg.preferences.get(RANGE_KEY));
    if (restored !== undefined) range = restored;
    return api.insightsOverview(overviewDays(restored));
  });
  const ranges = $derived(overview.data?.ranges ?? FIRST_RANGES);
  const deferLowerBlocks = typeof IntersectionObserver !== 'undefined';
  const pendingIds = new Map<Element, string>();
  let nearViewport: IntersectionObserver | undefined;

  function reveal(id: string): void {
    if (revealed.includes(id)) return;
    revealed = [...revealed, id];
  }

  function watch(node: HTMLElement, id: string): { destroy(): void } {
    pendingIds.set(node, id);
    nearViewport ??= new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue;
          const blockId = pendingIds.get(entry.target);
          if (blockId) reveal(blockId);
        }
      },
      { rootMargin: `${NEAR_VIEWPORT_PX}px 0px` },
    );
    nearViewport.observe(node);
    return {
      destroy() {
        nearViewport?.unobserve(node);
        pendingIds.delete(node);
      },
    };
  }

  onDestroy(() => nearViewport?.disconnect());

  $effect(() => tg.onActivated(() => void overview.load()));

  // The chosen period applies to every block (MA-INS-1, VA-MA-12).
  function changeRange(): void {
    tg.haptic('selection');
    const chosen = range;
    if (chosen === COMPARE || chosen === RHYTHM) return;
    tg.preferences.set(RANGE_KEY, String(chosen));
    if (chosen === YEAR) return;
    void overview.load(() => api.insightsOverview(chosen));
  }

  /** A tile leads to its block: the block's heading takes the focus and comes into view. */
  async function open(block: InsightBlock): Promise<void> {
    reveal(block.id);
    await tick();
    const heading = document.getElementById(`insight-${block.id}`);
    heading?.focus({ preventScroll: true });
    heading?.scrollIntoView?.({ block: 'start', behavior: isReducedMotion(tg) ? 'auto' : 'smooth' });
  }

  const withData = (data: InsightsOverview): boolean => data.blocks.some((block) => !block.noDataText);
</script>

<Screen title={en.insights.title} subtitle={typeof range === 'number' ? overview.data?.rangeLabel : undefined}>
  <fieldset class="section">
    <legend class="visually-hidden">{en.insights.range}</legend>
    <div class="segmented">
      {#each ranges as option (option)}
        <label>
          <input type="radio" name="insights-range" value={option} bind:group={range} onchange={changeRange} />
          <span class="numeric">{en.insights.days(option)}</span>
        </label>
      {/each}
      {#each tabs as [value, label] (value)}
        <label>
          <input type="radio" name="insights-range" {value} bind:group={range} onchange={changeRange} />
          <span>{label}</span>
        </label>
      {/each}
    </div>
  </fieldset>

  {#if range === YEAR}
    <YearView />
  {:else if range === COMPARE}
    <CompareView />
  {:else if range === RHYTHM}
    <RhythmView />
  {:else}
    <AsyncView resource={overview} loadingLabel={en.insights.loading} skeletonRows={4}>
      {#snippet children(data)}
        {#if data.notice}<p class="muted notice">{data.notice}</p>{/if}
        <!-- The week's hit days with the counts switch on (ROADMAP G9.4): a count of days with records, nothing to keep. -->
        {#if data.counts}<p class="muted notice">{data.counts}</p>{/if}
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
          {#each data.blocks as block, index (block.id)}
            {#if index < EAGER_BLOCKS || revealed.includes(block.id) || !deferLowerBlocks}
              <DomainBlock {block} />
            {:else}
              <div class="later" data-insight-lazy={block.id} use:watch={block.id}></div>
            {/if}
          {/each}
        </div>
        {#if data.blocks.length > 0}<MonthAndPatterns />{/if}
        {#if withData(data)}<ShareCards />{/if}
        <div class="section section-body">
          <LinkRow path="/insights/history" label={en.insights.history} hint={en.insights.historyHint} />
          <LinkRow path="/insights/reports" label={en.reports.title} hint={en.reports.hint} />
        </div>
      {/snippet}
    </AsyncView>
  {/if}
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

  .later {
    min-block-size: 1px;
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
