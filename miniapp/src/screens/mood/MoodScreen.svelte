<script lang="ts">
  import { unlessOff } from '../../api/client';
  import { OFF, type MoodCheckin, type MoodSummary, type Off } from '../../api/types';
  import { useApp } from '../../app/context';
  import MoodBlob from '../../charts/MoodBlob.svelte';
  import { en } from '../../i18n/en';
  import { moodAssets, type MoodAssets } from '../../state/mood-assets';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import EmptyState from '../../ui/EmptyState.svelte';
  import OffState from '../../ui/OffState.svelte';
  import Screen from '../../ui/Screen.svelte';
  import MoodStatement from './MoodStatement.svelte';
  import MoodWeek from './MoodWeek.svelte';

  interface Loaded {
    readonly summary: MoodSummary;
    readonly checkins: readonly MoodCheckin[];
    readonly assets: MoodAssets;
  }

  const { api, tg, router, toasts } = useApp();

  async function read(): Promise<Loaded | Off> {
    const summary = await unlessOff(api.moodSummary());
    if (summary === OFF) return OFF;
    const [checkins, assets] = await Promise.all([
      api.moodCheckins(summary.today.date, summary.today.date),
      moodAssets(api),
    ]);
    return { summary, checkins, assets };
  }

  const mood = new Resource<Loaded | Off>(read);
  $effect(() => tg.onActivated(() => void mood.load()));

  const checkInNow = () => router.navigate('/log/mood/new');

  async function remove(checkin: MoodCheckin): Promise<void> {
    try {
      await api.removeCheckin(checkin.id);
      toasts.show(en.mood.removed);
      await mood.load();
    } catch {
      tg.haptic('error');
      toasts.show(en.errors.notSaved);
    }
  }
</script>

<Screen title={en.mood.title}>
  {#snippet actions()}
    {#if mood.data && mood.data !== OFF}
      <button type="button" class="btn btn-filled" onclick={checkInNow}>{en.mood.checkIn}</button>
    {/if}
  {/snippet}

  <AsyncView resource={mood} loadingLabel={en.mood.loading} skeletonRows={3}>
    {#snippet children(data)}
      {#if data === OFF}
        <div class="section"><OffState domain="mood" /></div>
      {:else}
        {@const latest = data.summary.today.latest}
        {#if latest}
          <!-- The latest check-in of the day large, labelled as the latest (MA-MOOD-8). -->
          <section class="section section-body pad latest" aria-labelledby="mood-latest">
            <MoodBlob asset={data.assets.asset} stateId={latest.blobState} label={latest.label} size={120} breathe />
            <div>
              <h2 class="muted" id="mood-latest">{en.mood.latest(latest.time)}</h2>
              <p class="label">{latest.label}</p>
            </div>
          </section>
        {:else if data.summary.days.every((day) => day.checkIns === 0)}
          <div class="section"><EmptyState message={en.mood.empty} actionLabel={en.mood.checkIn} onAction={checkInNow} art="mood" /></div>
        {:else}
          <p class="section section-body pad">{data.summary.today.text}</p>
        {/if}

        {@const others = data.checkins.filter((checkin) => checkin.id !== latest?.id)}
        {#if others.length > 0}
          <section class="section" aria-labelledby="mood-today">
            <h2 class="section-title" id="mood-today">{en.mood.earlier}</h2>
            <ul class="section-body">
              {#each [...others].reverse() as checkin (checkin.id)}
                <li class="row">
                  <MoodBlob asset={data.assets.asset} stateId={checkin.blobState} size={36} />
                  <span class="time numeric">{checkin.time}</span>
                  <span class="text">{checkin.label}</span>
                  <button type="button" class="btn" aria-label={en.mood.remove(checkin.label, checkin.time)} onclick={() => remove(checkin)}>
                    {en.water.removeShort}
                  </button>
                </li>
              {/each}
            </ul>
          </section>
        {/if}

        <MoodWeek days={data.summary.days} asset={data.assets.asset} />
        <MoodStatement statement={data.assets.scale.statement} />
      {/if}
    {/snippet}
  </AsyncView>
</Screen>

<style>
  .latest {
    display: flex;
    align-items: center;
    gap: var(--dg-space-4);
  }

  h2 {
    font-weight: 400;
  }

  .label {
    font-size: var(--dg-text-title);
    font-weight: 600;
    line-height: 1.2;
  }

  .row {
    display: flex;
    align-items: center;
    gap: var(--dg-space-3);
    min-block-size: var(--dg-row-two-line);
    padding-inline: var(--dg-space-4) var(--dg-space-2);
  }

  .time {
    color: var(--dg-text-muted);
  }

  .text {
    flex: 1;
  }
</style>
