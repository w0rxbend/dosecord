<script lang="ts">
  import { OFF } from '../../api/types';
  import { useApp } from '../../app/context';
  import MoodBlob from '../../charts/MoodBlob.svelte';
  import Vessel from '../../charts/Vessel.svelte';
  import { en } from '../../i18n/en';
  import type { DayTracking } from './tracking';

  interface Props {
    tracking: DayTracking;
    isToday: boolean;
    onAddWater: (amountMl: number) => void;
  }

  let { tracking, isToday, onAddWater }: Props = $props();
  const { router } = useApp();
  const go = (event: MouseEvent, path: string) => {
    event.preventDefault();
    void router.navigate(path);
  };
</script>

<!-- Today: water with one tap per amount and the latest check-in. Another day: everything recorded,
     in time order, each entry in the server's words (MINI_APP 7.13). -->
{#if tracking.water !== OFF}
  {@const water = tracking.water}
  <section class="section" aria-labelledby="day-water">
    <h2 class="section-title" id="day-water">{en.today.water}</h2>
    <div class="section-body pad card">
      {#if water.day}
        <div class="lead">
          {#if water.day.goalMl !== undefined && water.day.vesselLabel}
            <Vessel valueMl={water.day.totalMl} goalMl={water.day.goalMl} label={water.day.vesselLabel} size={40} />
          {/if}
          <p class="numeric figure">{water.day.text}</p>
          <a class="btn" href={router.hrefFor('/log/water')} onclick={(event) => go(event, '/log/water')}>{en.insights.more} ›</a>
        </div>
      {:else if water.entries.length === 0}
        <p class="muted">{isToday ? en.water.nothing : en.day.noWater}</p>
      {/if}
      {#if isToday && water.presetsMl.length > 0}
        <div class="btn-row">
          {#each water.presetsMl as amount (amount)}
            <button type="button" class="btn btn-tinted numeric" aria-label={en.water.addLabel(amount)} onclick={() => onAddWater(amount)}>
              {en.water.add(amount)}
            </button>
          {/each}
        </div>
      {/if}
      {#if !isToday && water.entries.length > 0}
        <ul class="entries">
          {#each water.entries as entry (entry.id)}
            <li><span class="time numeric">{entry.time}</span> {en.day.water(entry.text)}</li>
          {/each}
        </ul>
      {/if}
    </div>
  </section>
{/if}

{#if tracking.mood !== OFF}
  {@const mood = tracking.mood}
  <section class="section" aria-labelledby="day-mood">
    <h2 class="section-title" id="day-mood">{en.log.mood}</h2>
    <div class="section-body pad card">
      {#if mood.checkins.length === 0}
        <p class="muted">{isToday ? (mood.day?.text ?? en.mood.empty) : en.day.noMood}</p>
      {/if}
      <ul class="entries">
        {#each mood.checkins as checkin (checkin.id)}
          <li class="checkin">
            {#if tracking.assets}<MoodBlob asset={tracking.assets.asset} stateId={checkin.blobState} size={40} />{/if}
            <span><span class="time numeric">{checkin.time}</span> {isToday ? checkin.label : en.day.mood(checkin.label)}</span>
          </li>
        {/each}
      </ul>
      {#if isToday}
        <div class="btn-row">
          <a class="btn btn-tinted" href={router.hrefFor('/log/mood/new')} onclick={(event) => go(event, '/log/mood/new')}>{en.mood.checkIn}</a>
        </div>
      {/if}
    </div>
  </section>
{/if}

<style>
  .card {
    display: grid;
    gap: var(--dg-space-3);
  }

  .lead {
    display: flex;
    align-items: center;
    gap: var(--dg-space-3);
  }

  .figure {
    flex: 1;
    font-weight: 600;
  }

  .entries {
    display: grid;
    gap: var(--dg-space-2);
  }

  .checkin {
    display: flex;
    align-items: center;
    gap: var(--dg-space-3);
  }

  .time {
    color: var(--dg-text-muted);
  }
</style>
