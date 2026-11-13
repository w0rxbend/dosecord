<script lang="ts">
  import type { Domain, InsightCalendar } from '../../api/types';
  import { useApp } from '../../app/context';
  import { historyDayPath } from '../../app/routes';
  import { en } from '../../i18n/en';
  import { moodAssets, type MoodAssets } from '../../state/mood-assets';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import EmptyState from '../../ui/EmptyState.svelte';
  import Screen from '../../ui/Screen.svelte';
  import CalendarGrid from './CalendarGrid.svelte';
  import CalendarLegend from './CalendarLegend.svelte';

  interface Shown {
    readonly calendar: InsightCalendar | null;
    readonly assets?: MoodAssets;
  }

  const DOMAINS: readonly Domain[] = ['medication', 'hydration', 'mood'];
  const { api, tg, router } = useApp();

  let domain = $state<Domain | null>(null);
  let showValues = $state(false);
  let scheme = $state(tg.theme().scheme);
  $effect(() => tg.onThemeChanged(() => (scheme = tg.theme().scheme)));

  // The first domain the account turned on; one month is one request (MA-HIST-7).
  async function read(month?: string): Promise<Shown> {
    if (domain === null) {
      const consents = await api.consents();
      domain = DOMAINS.find((candidate) => consents.items.some((item) => item.purpose === candidate && item.granted)) ?? null;
    }
    if (domain === null) return { calendar: null };
    const calendar = await api.calendar(domain, month);
    return { calendar, assets: domain === 'mood' ? await moodAssets(api) : undefined };
  }

  const shown = new Resource<Shown>(() => read());

  function showMonth(month: string): void {
    tg.haptic('selection');
    void shown.load(() => read(month));
  }

  function changeDomain(): void {
    tg.haptic('selection');
    const month = shown.data?.calendar?.month;
    void shown.load(() => read(month));
  }
</script>

<Screen title={en.history.title} subtitle={shown.data?.calendar?.monthLabel}>
  {#snippet actions()}
    {#if shown.data?.calendar}
      {@const calendar = shown.data.calendar}
      <button type="button" class="btn" aria-label={en.history.previous} onclick={() => showMonth(calendar.previousMonth)}>
        <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path d="M15 5l-7 7 7 7" /></svg>
      </button>
      <button
        type="button"
        class="btn"
        aria-label={en.history.next}
        disabled={!calendar.nextMonth}
        onclick={() => calendar.nextMonth && showMonth(calendar.nextMonth)}
      >
        <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path d="M9 5l7 7-7 7" /></svg>
      </button>
    {/if}
  {/snippet}

  <AsyncView resource={shown} loadingLabel={en.history.loading} skeletonRows={5}>
    {#snippet children(data)}
      {#if data.calendar === null}
        <div class="section">
          <EmptyState message={en.log.empty} actionLabel={en.log.choose} onAction={() => router.navigate('/settings/privacy')} />
        </div>
      {:else}
        {@const calendar = data.calendar}
        <fieldset class="section">
          <legend class="visually-hidden">{en.history.domain}</legend>
          <div class="chips">
            {#each calendar.domains as option (option)}
              <label class="chip">
                <input type="radio" name="history-domain" value={option} bind:group={domain} onchange={changeDomain} />
                <span>{en.history.domains[option]}</span>
              </label>
            {/each}
          </div>
        </fieldset>

        <div class="section section-body pad" aria-busy={shown.loading}>
          <CalendarGrid
            {calendar}
            {showValues}
            {scheme}
            asset={data.assets?.asset}
            onOpen={(date) => router.navigate(historyDayPath(date))}
            onMonth={showMonth}
          />
        </div>

        <CalendarLegend {calendar} {scheme} asset={data.assets?.asset} bind:showValues />

        <p class="section section-body pad numeric">{calendar.summary}</p>
      {/if}
    {/snippet}
  </AsyncView>
</Screen>

<style>
  svg {
    fill: none;
    stroke: currentColor;
    stroke-width: 2;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
</style>
