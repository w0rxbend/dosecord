<script lang="ts">
  import { newIdempotencyKey, unlessOff } from '../../api/client';
  import { OFF, type Occurrence, type OccurrenceAction, type OccurrenceActionResult, type Off, type TodayView } from '../../api/types';
  import { useApp } from '../../app/context';
  import { dayPath } from '../../app/routes';
  import { en } from '../../i18n/en';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import EmptyState from '../../ui/EmptyState.svelte';
  import Screen from '../../ui/Screen.svelte';
  import DayLine from './DayLine.svelte';
  import DayTrackers from './DayTrackers.svelte';
  import { optimistic, replaceOccurrence } from './group';
  import SetupCard from './SetupCard.svelte';
  import TodayDoses from './TodayDoses.svelte';
  import { readTracking, trackingMarks, type DayTracking } from './tracking';

  /** `title` is set when the day is opened from the history calendar (S-13). */
  let { date, title }: { date?: string; title?: string } = $props();

  const { api, tg, router, toasts } = useApp();
  const day = new Resource<TodayView | Off>(() => unlessOff(api.today(date)));
  const tracking = new Resource<DayTracking>(() => readTracking(api, date));
  const isToday = $derived(date === undefined || (day.data !== undefined && day.data !== OFF && day.data.isToday));

  // Without a change feed, the visible screen is fetched again when the app returns (8.7).
  $effect(() =>
    tg.onActivated(() => {
      void day.load();
      void tracking.load();
    }),
  );

  function show(occurrence: Occurrence): void {
    const view = day.data;
    if (view && view !== OFF) day.set({ ...view, occurrences: replaceOccurrence(view.occurrences, occurrence) });
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

  // One tap adds water; the figures follow at once and are rolled back if the request fails.
  async function addWater(amountMl: number): Promise<void> {
    const current = tracking.data;
    if (!current || current.water === OFF || !current.water.day) return;
    const before = current;
    const water = current.water;
    const day = water.day!;
    tracking.set({ ...current, water: { ...water, day: { ...day, totalMl: day.totalMl + amountMl, entries: day.entries + 1 } } });
    try {
      const logged = await api.addWater(amountMl, newIdempotencyKey());
      tg.haptic(logged.goalReachedNow ? 'success' : 'impact-light');
      toasts.show(en.today.waterAdded(amountMl));
      void tracking.load();
    } catch {
      tracking.set(before);
      tg.haptic('error');
      toasts.show(en.errors.notSaved);
    }
  }

  const everythingOff = (view: TodayView | Off) =>
    view === OFF && tracking.data !== undefined && tracking.data.water === OFF && tracking.data.mood === OFF;
</script>

<Screen title={title ?? en.today.title} subtitle={day.data && day.data !== OFF ? day.data.dateLabel : undefined}>
  {#snippet actions()}
    {#if day.data && day.data !== OFF}
      {@const view = day.data}
      <button type="button" class="btn" aria-label={en.today.previousDay} onclick={() => router.replace(dayPath(view.previousDate))}>
        <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path d="M15 5l-7 7 7 7" /></svg>
      </button>
      <button type="button" class="btn" aria-label={en.today.nextDay} onclick={() => router.replace(dayPath(view.nextDate))}>
        <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path d="M9 5l7 7-7 7" /></svg>
      </button>
    {/if}
    {#if date === undefined}
      <!-- The native settings item sits in a context menu; Today carries a visible control (MA-NAV-3). -->
      <button type="button" class="btn" aria-label={en.settings.open} onclick={() => router.navigate('/settings')}>
        <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">
          <path d="M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6zM19 12l2-1-1-3-2 .2-1.3-1.3.2-2-3-1-1 2h-1.8l-1-2-3 1 .2 2L6 7.2 4 7l-1 3 2 1v1.8l-2 1 1 3 2-.2 1.3 1.3-.2 2 3 1 1-2h1.8l1 2 3-1-.2-2 1.3-1.3 2 .2 1-3-2-1z" />
        </svg>
      </button>
    {/if}
  {/snippet}

  <AsyncView resource={day} loadingLabel={en.today.loading} skeletonRows={4}>
    {#snippet children(view)}
      {#if view !== OFF}
        {#if !view.isToday && title === undefined}
          <p class="section"><button type="button" class="btn btn-tinted" onclick={() => router.replace('/')}>{en.today.backToToday}</button></p>
        {/if}
        {#if date === undefined}<SetupCard />{/if}
        <div class="section section-body pad"><DayLine marks={[...view.dayLine, ...trackingMarks(tracking.data)]} nowMinute={view.nowMinute} /></div>
        {#if view.occurrences.length === 0}
          <div class="section">
            <EmptyState message={en.today.empty} actionLabel={en.today.emptyAction} onAction={() => router.navigate('/meds/new')} art="medication" />
          </div>
        {:else}
          {#if view.answerInChat}<p class="note answer">{en.today.answerInChat}</p>{/if}
          <TodayDoses occurrences={view.occurrences} readOnly={view.readOnly} planned={!view.answerInChat} onAction={act} />
        {/if}
      {:else if everythingOff(view)}
        <div class="section">
          <EmptyState message={en.log.empty} actionLabel={en.log.choose} onAction={() => router.navigate('/settings/privacy')} art="all" />
        </div>
      {/if}
      {#if tracking.data}
        <DayTrackers tracking={tracking.data} {isToday} onAddWater={addWater} />
      {/if}
    {/snippet}
  </AsyncView>
</Screen>

<style>
  /* Apart from the first group title below it, which has the same colour. */
  .answer {
    margin-block-end: var(--dg-space-4);
  }

  svg {
    fill: none;
    stroke: currentColor;
    stroke-width: 2;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
</style>
