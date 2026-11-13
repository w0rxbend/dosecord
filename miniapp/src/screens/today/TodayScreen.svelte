<script lang="ts">
  import { newIdempotencyKey } from '../../api/client';
  import type { Occurrence, OccurrenceAction, OccurrenceActionResult, TodayView } from '../../api/types';
  import { useApp } from '../../app/context';
  import { dayPath } from '../../app/routes';
  import { en } from '../../i18n/en';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import EmptyState from '../../ui/EmptyState.svelte';
  import Screen from '../../ui/Screen.svelte';
  import DayLine from './DayLine.svelte';
  import { optimistic, replaceOccurrence } from './group';
  import TodayDoses from './TodayDoses.svelte';
  import WaterRow from './WaterRow.svelte';

  let { date }: { date?: string } = $props();

  const { api, tg, router, toasts } = useApp();
  const day = new Resource<TodayView>(() => api.today(date));

  // Without a change feed, the visible screen is fetched again when the app returns (8.7).
  $effect(() => tg.onActivated(() => void day.load()));

  function show(occurrence: Occurrence): void {
    const view = day.data;
    if (view) day.set({ ...view, occurrences: replaceOccurrence(view.occurrences, occurrence) });
  }

  function announce(result: OccurrenceActionResult, action: OccurrenceAction): void {
    const { occurrence, alreadyResolvedIn } = result;
    const undo = { label: en.common.undo, run: () => void act(occurrence, { kind: 'undo' }) };
    if (alreadyResolvedIn) {
      const time = occurrence.resolvedTime;
      toasts.show(time ? en.today.alreadyTaken(time, alreadyResolvedIn) : en.today.alreadyResolved(alreadyResolvedIn));
    } else if (action.kind === 'taken') {
      tg.haptic('success');
      toasts.show(en.today.markedTaken(occurrence.resolvedTime ?? occurrence.scheduledTime), undo);
    } else if (action.kind === 'skipped') {
      toasts.show(en.today.markedSkipped, undo);
    } else if (action.kind === 'snoozed') {
      tg.haptic('impact-light');
      toasts.show(en.today.snoozedUntil(occurrence.snoozedUntil ?? ''), undo);
    }
  }

  async function act(occurrence: Occurrence, action: OccurrenceAction): Promise<void> {
    // The key is made when the person acts, so a retry repeats it (MA-CONC-1).
    const key = newIdempotencyKey();
    show(optimistic(occurrence, action, new Date()));
    try {
      const result = await api.actOnOccurrence(occurrence.id, action, key);
      show(result.occurrence);
      announce(result, action);
      void day.load();
    } catch {
      show(occurrence);
      tg.haptic('error');
      toasts.show(en.errors.notSaved);
    }
  }

  async function addWater(amountMl: number): Promise<void> {
    const view = day.data;
    if (!view?.hydration) return;
    const key = newIdempotencyKey();
    const before = view.hydration;
    day.set({ ...view, hydration: { ...before, totalMl: before.totalMl + amountMl } });
    try {
      await api.addHydration(amountMl, key);
      tg.haptic('impact-light');
      toasts.show(en.today.waterAdded(amountMl));
      void day.load();
    } catch {
      if (day.data) day.set({ ...day.data, hydration: before });
      tg.haptic('error');
      toasts.show(en.errors.notSaved);
    }
  }
</script>

<Screen title={en.today.title} subtitle={day.data?.dateLabel}>
  {#snippet actions()}
    {#if day.data}
      {@const view = day.data}
      <button type="button" class="btn" aria-label={en.today.previousDay} onclick={() => router.replace(dayPath(view.previousDate))}>
        <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path d="M15 5l-7 7 7 7" /></svg>
      </button>
      <button type="button" class="btn" aria-label={en.today.nextDay} onclick={() => router.replace(dayPath(view.nextDate))}>
        <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path d="M9 5l7 7-7 7" /></svg>
      </button>
    {/if}
  {/snippet}

  <AsyncView resource={day} loadingLabel={en.today.loading} skeletonRows={4}>
    {#snippet children(view)}
      {#if !view.isToday}
        <p class="section"><button type="button" class="btn btn-tinted" onclick={() => router.replace('/')}>{en.today.backToToday}</button></p>
      {/if}
      <div class="section section-body pad"><DayLine marks={view.dayLine} nowMinute={view.nowMinute} /></div>
      {#if view.occurrences.length === 0}
        <div class="section">
          <EmptyState message={en.today.empty} actionLabel={en.today.emptyAction} onAction={() => router.navigate('/meds/new')} />
        </div>
      {:else}
        <TodayDoses occurrences={view.occurrences} readOnly={view.readOnly} onAction={act} />
      {/if}
      {#if view.hydration}
        <WaterRow hydration={view.hydration} canAdd={view.isToday} onAdd={addWater} />
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
