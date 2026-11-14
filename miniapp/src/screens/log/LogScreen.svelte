<script lang="ts">
  import { unlessOff } from '../../api/client';
  import {
    OFF,
    type MoodSummary,
    type NutritionSummary,
    type Off,
    type SleepSummary,
    type WaterSummary,
  } from '../../api/types';
  import { useApp } from '../../app/context';
  import MoodBlob from '../../charts/MoodBlob.svelte';
  import Vessel from '../../charts/Vessel.svelte';
  import { en } from '../../i18n/en';
  import { moodAssets, type MoodAssets } from '../../state/mood-assets';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import EmptyState from '../../ui/EmptyState.svelte';
  import Screen from '../../ui/Screen.svelte';

  interface Hub {
    readonly water: WaterSummary | Off;
    readonly mood: MoodSummary | Off;
    readonly sleep: SleepSummary | Off;
    readonly food: NutritionSummary | Off;
    readonly assets?: MoodAssets;
  }

  const { api, tg, router } = useApp();

  // The same reads as the domain screens; a domain whose consent is missing reads as off.
  async function read(): Promise<Hub> {
    const [water, mood, sleep, food] = await Promise.all([
      unlessOff(api.waterSummary()),
      unlessOff(api.moodSummary()),
      unlessOff(api.sleepSummary()),
      unlessOff(api.nutritionSummary()),
    ]);
    return { water, mood, sleep, food, assets: mood === OFF ? undefined : await moodAssets(api) };
  }

  const hub = new Resource<Hub>(read);
  $effect(() => tg.onActivated(() => void hub.load()));

  const NAMES = { hydration: en.log.water, mood: en.log.mood, sleep: en.log.sleep, nutrition: en.log.food } as const;

  const off = (data: Hub): ('hydration' | 'mood' | 'sleep' | 'nutrition')[] => [
    ...(data.water === OFF ? (['hydration'] as const) : []),
    ...(data.mood === OFF ? (['mood'] as const) : []),
    ...(data.sleep === OFF ? (['sleep'] as const) : []),
    ...(data.food === OFF ? (['nutrition'] as const) : []),
  ];
</script>

<Screen title={en.log.title}>
  <AsyncView resource={hub} loadingLabel={en.log.loading} skeletonRows={2}>
    {#snippet children(data)}
      {#if data.water === OFF && data.mood === OFF && data.sleep === OFF && data.food === OFF}
        <!-- MA-LOG-2: no optional domain is on. -->
        <div class="section">
          <EmptyState message={en.log.empty} actionLabel={en.log.choose} onAction={() => router.navigate('/settings/privacy')} />
        </div>
      {:else}
        <!-- Enabled domains first, each with one line of today's state (MA-LOG-1). -->
        <ul class="section section-body">
          {#if data.water !== OFF}
            {@const water = data.water}
            <li>
              <a class="row" href={router.hrefFor('/log/water')} onclick={(event) => { event.preventDefault(); void router.navigate('/log/water'); }}>
                {#if water.today.goalMl !== undefined && water.today.vesselLabel}
                  <Vessel valueMl={water.today.totalMl} goalMl={water.today.goalMl} label={water.today.vesselLabel} size={28} />
                {/if}
                <span class="text"><span class="name">{en.log.water}</span><span class="muted numeric">{water.today.text}</span></span>
                <span class="chevron" aria-hidden="true">›</span>
              </a>
            </li>
          {/if}
          {#if data.mood !== OFF}
            {@const mood = data.mood}
            <li>
              <a class="row" href={router.hrefFor('/log/mood')} onclick={(event) => { event.preventDefault(); void router.navigate('/log/mood'); }}>
                {#if data.assets}<MoodBlob asset={data.assets.asset} stateId={mood.today.blobState} size={40} />{/if}
                <span class="text"><span class="name">{en.log.mood}</span><span class="muted">{mood.today.text}</span></span>
                <span class="chevron" aria-hidden="true">›</span>
              </a>
            </li>
          {/if}
          {#if data.sleep !== OFF}
            {@const sleep = data.sleep}
            <li>
              <a class="row" href={router.hrefFor('/log/sleep')} onclick={(event) => { event.preventDefault(); void router.navigate('/log/sleep'); }}>
                <span class="text"><span class="name">{en.log.sleep}</span><span class="muted">{sleep.status.text}</span></span>
                <span class="chevron" aria-hidden="true">›</span>
              </a>
            </li>
          {/if}
          {#if data.food !== OFF}
            {@const food = data.food}
            <li>
              <a class="row" href={router.hrefFor('/log/food')} onclick={(event) => { event.preventDefault(); void router.navigate('/log/food'); }}>
                <span class="text"><span class="name">{en.log.food}</span><span class="muted">{food.today.text}</span></span>
                <span class="chevron" aria-hidden="true">›</span>
              </a>
            </li>
          {/if}
        </ul>
        {#if off(data).length > 0}
          <section class="section" aria-labelledby="log-off">
            <h2 class="section-title" id="log-off">{en.log.notOn}</h2>
            <div class="section-body pad off">
              {#each off(data) as domain (domain)}
                <p><span class="name">{NAMES[domain]}</span> <span class="muted">{en.off.howTo[domain]}</span></p>
              {/each}
              <button type="button" class="btn btn-tinted" onclick={() => router.navigate('/settings/privacy')}>{en.log.choose}</button>
            </div>
          </section>
        {/if}
      {/if}
    {/snippet}
  </AsyncView>
</Screen>

<style>
  .row {
    display: flex;
    align-items: center;
    gap: var(--dg-space-3);
    min-block-size: var(--dg-row-two-line);
    padding: var(--dg-space-2) var(--dg-space-4);
    color: inherit;
    text-decoration: none;
  }

  .text {
    display: grid;
    flex: 1;
  }

  .name {
    font-weight: 600;
  }

  .chevron {
    color: var(--dg-text-muted);
    font-size: var(--dg-text-title);
  }

  .off {
    display: grid;
    gap: var(--dg-space-3);
    justify-items: start;
  }
</style>
