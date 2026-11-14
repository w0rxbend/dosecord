<script lang="ts">
  import { ApiError, newIdempotencyKey, toApiError, unlessOff, type FieldError } from '../../api/client';
  import { OFF, type Off, type SleepCheckinKind, type SleepEpisode, type SleepSummary } from '../../api/types';
  import { useApp } from '../../app/context';
  import ChartFigure from '../../charts/ChartFigure.svelte';
  import { t as en, errorText } from '../../i18n/index.svelte';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import OffState from '../../ui/OffState.svelte';
  import Screen from '../../ui/Screen.svelte';
  import NightForm from './NightForm.svelte';
  import { toEpisodeInput, type NightDraft } from './night';
  import SleepGoalForm from './SleepGoalForm.svelte';

  interface Loaded {
    readonly summary: SleepSummary | Off;
    readonly episodes: readonly SleepEpisode[];
  }

  const { api, tg, toasts } = useApp();

  async function read(): Promise<Loaded> {
    const summary = await unlessOff(api.sleepSummary());
    if (summary === OFF) return { summary, episodes: [] };
    const days = summary.days;
    const episodes = days.length === 0 ? [] : await api.sleepEpisodes(days[0]!.date, days.at(-1)!.date);
    return { summary, episodes: [...episodes].reverse() };
  }

  const sleep = new Resource<Loaded>(read);
  $effect(() => tg.onActivated(() => void sleep.load()));

  let busy = $state(false);
  let errors = $state<readonly FieldError[]>([]);
  let askNight = $state(false);

  // "Going to sleep" and "I'm up": one tap, one request with the key made at the tap.
  async function tap(kind: SleepCheckinKind): Promise<void> {
    busy = true;
    try {
      const status = await api.sleepCheckIn(kind, newIdempotencyKey());
      tg.haptic('impact-light');
      askNight = status.needsBedtime;
      if (!status.needsBedtime) toasts.show(kind === 'going_to_sleep' ? en.sleep.asleepSaved : en.sleep.upSaved);
      await sleep.load();
    } catch (cause) {
      tg.haptic('error');
      toasts.show(errorText(toApiError(cause).code));
    } finally {
      busy = false;
    }
  }

  async function save(draft: NightDraft): Promise<void> {
    busy = true;
    try {
      await api.addSleep(toEpisodeInput(draft), newIdempotencyKey());
      errors = [];
      askNight = false;
      tg.haptic('success');
      toasts.show(en.sleep.saved);
      await sleep.load();
    } catch (cause) {
      tg.haptic('error');
      if (cause instanceof ApiError && cause.code === 'validation') errors = cause.fields;
      else toasts.show(errorText(toApiError(cause).code));
    } finally {
      busy = false;
    }
  }

  async function remove(episode: SleepEpisode): Promise<void> {
    try {
      await api.removeSleep(episode.id);
      toasts.show(en.sleep.removed);
      await sleep.load();
    } catch {
      tg.haptic('error');
      toasts.show(en.errors.notSaved);
    }
  }
</script>

<Screen title={en.sleep.title}>
  <AsyncView resource={sleep} loadingLabel={en.sleep.loading} skeletonRows={4}>
    {#snippet children(data)}
      {#if data.summary === OFF}
        <div class="section"><OffState domain="sleep" /></div>
      {:else}
        {@const summary = data.summary}
        {@const status = summary.status}
        <section class="section" aria-labelledby="sleep-now">
          <h2 class="section-title" id="sleep-now">{en.sleep.now}</h2>
          <div class="section-body pad card">
            <p class="metric" aria-live="polite">{status.text}</p>
            {#if status.lastNight && status.openSince === undefined}
              <p class="muted">{status.lastNight.source}</p>
            {/if}
            {#if status.goalText}<p class="muted">{status.goalText}</p>{/if}
            {#if status.openSince === undefined}
              <button type="button" class="btn btn-filled" disabled={busy} onclick={() => tap('going_to_sleep')}>{en.sleep.goingToSleep}</button>
            {:else}
              <button type="button" class="btn btn-filled" disabled={busy} onclick={() => tap('awake')}>{en.sleep.imUp}</button>
            {/if}
            {#if askNight}<p role="status">{en.sleep.askNight}</p>{/if}
          </div>
        </section>

        <section class="section" aria-labelledby="sleep-add">
          <h2 class="section-title" id="sleep-add">{en.sleep.add}</h2>
          <div class="section-body pad">
            <NightForm today={summary.days.at(-1)?.date ?? ''} {busy} {errors} onSave={save} />
          </div>
        </section>

        <section class="section" aria-labelledby="sleep-nights">
          <h2 class="section-title" id="sleep-nights">{en.sleep.nights}</h2>
          <div class="section-body pad card">
            {#if summary.chart}<ChartFigure chart={summary.chart} id="sleep-week-chart" />{:else}<p>{summary.noDataText}</p>{/if}
            {#if data.episodes.length > 0}
              <ul class="entries">
                {#each data.episodes as episode (episode.id)}
                  <li class="row">
                    <span class="text">{episode.text}</span>
                    <button type="button" class="btn" aria-label={en.sleep.remove(episode.text)} onclick={() => remove(episode)}>
                      {en.water.removeShort}
                    </button>
                  </li>
                {/each}
              </ul>
            {/if}
          </div>
        </section>

        {#if summary.lines.length > 0}
          <section class="section" aria-labelledby="sleep-figures">
            <h2 class="section-title" id="sleep-figures">{en.sleep.averages}</h2>
            <ul class="section-body pad lines">
              {#each summary.lines as line (line)}<li>{line}</li>{/each}
            </ul>
          </section>
        {/if}

        <section class="section" aria-labelledby="sleep-goal">
          <h2 class="section-title" id="sleep-goal">{en.sleep.goal}</h2>
          <div class="section-body pad"><SleepGoalForm onSaved={() => void sleep.load()} /></div>
        </section>
      {/if}
    {/snippet}
  </AsyncView>
</Screen>

<style>
  .card {
    display: grid;
    gap: var(--dg-space-3);
    justify-items: start;
  }

  .metric {
    font-size: var(--dg-text-title);
    font-weight: 600;
    line-height: 1.2;
  }

  .entries,
  .lines {
    display: grid;
    gap: var(--dg-space-2);
    inline-size: 100%;
  }

  .row {
    display: flex;
    align-items: center;
    gap: var(--dg-space-3);
  }

  .text {
    flex: 1;
  }
</style>
