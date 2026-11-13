<script lang="ts">
  import { unlessOff } from '../../api/client';
  import { OFF, type MoodSummary, type Off, type WaterSummary } from '../../api/types';
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
    readonly assets?: MoodAssets;
  }

  const { api, tg, router } = useApp();

  // The same reads as the domain screens; a domain whose consent is missing reads as off.
  async function read(): Promise<Hub> {
    const [water, mood] = await Promise.all([unlessOff(api.waterSummary()), unlessOff(api.moodSummary())]);
    return { water, mood, assets: mood === OFF ? undefined : await moodAssets(api) };
  }

  const hub = new Resource<Hub>(read);
  $effect(() => tg.onActivated(() => void hub.load()));

  const off = (data: Hub): ('hydration' | 'mood')[] => [
    ...(data.water === OFF ? (['hydration'] as const) : []),
    ...(data.mood === OFF ? (['mood'] as const) : []),
  ];
</script>

<Screen title={en.log.title}>
  <AsyncView resource={hub} loadingLabel={en.log.loading} skeletonRows={2}>
    {#snippet children(data)}
      {#if data.water === OFF && data.mood === OFF}
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
        </ul>
        {#if off(data).length > 0}
          <section class="section" aria-labelledby="log-off">
            <h2 class="section-title" id="log-off">{en.log.notOn}</h2>
            <div class="section-body pad off">
              {#each off(data) as domain (domain)}
                <p><span class="name">{domain === 'hydration' ? en.log.water : en.log.mood}</span> <span class="muted">{en.off.howTo[domain]}</span></p>
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
