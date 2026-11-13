<script lang="ts">
  import { newIdempotencyKey, toApiError, unlessOff } from '../../api/client';
  import { OFF, type Chart, type Off, type WaterEntry } from '../../api/types';
  import { useApp } from '../../app/context';
  import ChartFigure from '../../charts/ChartFigure.svelte';
  import Vessel from '../../charts/Vessel.svelte';
  import { en, errorText } from '../../i18n/en';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import OffState from '../../ui/OffState.svelte';
  import Screen from '../../ui/Screen.svelte';
  import OtherAmount from './OtherAmount.svelte';
  import WaterEntries from './WaterEntries.svelte';
  import { clockNow, withPending, type WaterView } from './water';

  interface Loaded {
    readonly view: WaterView | Off;
    readonly chart?: Chart;
    readonly noDataText?: string;
  }

  const UNDO_MS = 8000;
  const { api, tg, router, toasts } = useApp();

  async function read(): Promise<Loaded> {
    const summary = await unlessOff(api.waterSummary());
    if (summary === OFF) return { view: OFF };
    const entries = await api.waterEntries(summary.today.date, summary.today.date);
    return { view: { day: summary.today, entries, presetsMl: summary.presetsMl }, chart: summary.chart, noDataText: summary.noDataText };
  }

  const water = new Resource<Loaded>(read);
  $effect(() => tg.onActivated(() => void water.load()));

  /** Rows the person removed: hidden at once, deleted when the undo period is over. */
  let removing = $state<readonly string[]>([]);
  let busy = $state(false);

  function setView(view: WaterView): void {
    if (water.data) water.set({ ...water.data, view });
  }

  // One tap, one request with the key made at the tap; two taps are two entries (MA-HYD-1).
  async function add(amountMl: number): Promise<void> {
    const loaded = water.data;
    if (!loaded || loaded.view === OFF) return;
    const before = loaded.view;
    const key = newIdempotencyKey();
    setView(withPending(before, amountMl, clockNow()));
    try {
      const logged = await api.addWater(amountMl, key);
      // Reaching the goal: one success haptic and the full vessel, no message (MA-HYD-5).
      tg.haptic(logged.goalReachedNow ? 'success' : 'impact-light');
      toasts.show(en.water.added(amountMl), { label: en.common.undo, run: () => void remove(logged.entry, false) });
      await water.load();
    } catch (cause) {
      setView(before);
      tg.haptic('error');
      toasts.show(errorText(toApiError(cause).code === 'quota_exceeded' ? 'quota_exceeded' : 'notSaved'));
    }
  }

  // "Remove" needs no dialog for a small entry; it is undone within 8 seconds (MINI_APP 7.7).
  async function remove(entry: WaterEntry, offerUndo = true): Promise<void> {
    removing = [...removing, entry.id];
    if (!offerUndo) return void (await commitRemoval(entry));
    const timer = setTimeout(() => void commitRemoval(entry), UNDO_MS);
    toasts.show(en.water.removed(entry.amountMl), {
      label: en.common.undo,
      run: () => {
        clearTimeout(timer);
        removing = removing.filter((id) => id !== entry.id);
      },
    });
  }

  async function commitRemoval(entry: WaterEntry): Promise<void> {
    try {
      await api.removeWater(entry.id);
      await water.load();
    } catch {
      tg.haptic('error');
      toasts.show(en.errors.notSaved);
    } finally {
      removing = removing.filter((id) => id !== entry.id);
    }
  }

  async function addOther(amountMl: number): Promise<void> {
    busy = true;
    await add(amountMl);
    busy = false;
  }
</script>

<Screen title={en.water.title}>
  <AsyncView resource={water} loadingLabel={en.water.loading} skeletonRows={4}>
    {#snippet children(data)}
      {#if data.view === OFF}
        <div class="section"><OffState domain="hydration" /></div>
      {:else}
        {@const view = data.view}
        <section class="section section-body pad hero" aria-label={en.insights.vessel}>
          {#if view.day.goalMl !== undefined && view.day.vesselLabel}
            <Vessel valueMl={view.day.totalMl} goalMl={view.day.goalMl} label={view.day.vesselLabel} size={88} />
          {/if}
          <div class="figures">
            <p class="metric numeric" aria-live="polite">{view.day.text}</p>
            {#if view.day.goalMl === undefined}
              <p class="muted">{en.water.noGoal}</p>
              <button type="button" class="btn btn-tinted" onclick={() => router.navigate('/log/water/settings')}>{en.water.setGoal}</button>
            {/if}
          </div>
          <div class="presets">
            {#each view.presetsMl as amount (amount)}
              <button type="button" class="btn btn-filled numeric" aria-label={en.water.addLabel(amount)} onclick={() => add(amount)}>
                {en.water.add(amount)}
              </button>
            {/each}
          </div>
          <OtherAmount {busy} onAdd={addOther} />
        </section>

        <WaterEntries entries={view.entries.filter((entry) => !removing.includes(entry.id))} onRemove={(entry) => remove(entry)} />

        <section class="section" aria-labelledby="water-week">
          <h2 class="section-title" id="water-week">{en.water.week}</h2>
          <div class="section-body pad">
            {#if data.chart}<ChartFigure chart={data.chart} id="water-week-chart" />{:else}<p>{data.noDataText}</p>{/if}
          </div>
        </section>

        <div class="section section-body">
          <a class="link-row" href={router.hrefFor('/log/water/settings')} onclick={(event) => { event.preventDefault(); void router.navigate('/log/water/settings'); }}>
            {en.water.settings}<span aria-hidden="true">›</span>
          </a>
        </div>
      {/if}
    {/snippet}
  </AsyncView>
</Screen>

<style>
  .hero {
    display: grid;
    grid-template-columns: auto 1fr;
    gap: var(--dg-space-4);
    align-items: center;
  }

  .figures {
    display: grid;
    gap: var(--dg-space-2);
    justify-items: start;
  }

  .metric {
    font-size: var(--dg-text-title);
    font-weight: 600;
    line-height: 1.2;
  }

  .presets {
    grid-column: 1 / -1;
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(5.5rem, 1fr));
    gap: var(--dg-space-2);
  }

  .link-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    min-block-size: var(--dg-target);
    padding: 0 var(--dg-space-4);
    color: inherit;
    text-decoration: none;
  }
</style>
