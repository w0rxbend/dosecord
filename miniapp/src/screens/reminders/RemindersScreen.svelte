<script lang="ts">
  import { newIdempotencyKey, toApiError } from '../../api/client';
  import type { DayProfileInput, ReminderPresets, Reminders, ReminderTimeline } from '../../api/types';
  import { useApp } from '../../app/context';
  import { t as en, errorText } from '../../i18n/index.svelte';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import LinkRow from '../../ui/LinkRow.svelte';
  import Screen from '../../ui/Screen.svelte';
  import DayForm from './DayForm.svelte';
  import KindPresets from './KindPresets.svelte';
  import { clockIn, dayAndClockIn } from './time';

  interface Loaded {
    readonly view: Reminders;
    readonly presets: ReminderPresets;
    readonly timeline: ReminderTimeline;
  }

  const PAUSES: readonly number[] = [1, 3];
  const { api, tg, toasts } = useApp();
  const loaded = new Resource<Loaded>(async () => {
    const [view, presets, timeline] = await Promise.all([api.reminders(), api.reminderPresets(), api.reminderTimeline()]);
    return { view, presets, timeline };
  });
  $effect(() => tg.onActivated(() => void loaded.load()));

  let busy = $state(false);
  let failure = $state('');

  /** Every change answers with the whole hub; the timeline is read again because it follows the plans. */
  async function change(write: () => Promise<Reminders>, done?: string): Promise<void> {
    const before = loaded.data;
    if (!before) return;
    busy = true;
    failure = '';
    try {
      const view = await write();
      loaded.set({ ...before, view, timeline: await api.reminderTimeline() });
      tg.haptic('success');
      if (done) toasts.show(done);
    } catch (cause) {
      failure = errorText(toApiError(cause).code);
      tg.haptic('error');
    } finally {
      busy = false;
    }
  }

  function turnOn(presetId: string): void {
    const name = loaded.data?.presets.prompts.find((preset) => preset.id === presetId)?.name ?? '';
    void change(() => api.turnOnReminder(presetId, newIdempotencyKey()), en.reminders.on(name));
  }

  function turnOff(kind: string): void {
    void change(() => api.turnOffReminder(kind), en.reminders.turnedOff);
  }

  function pause(hours: number): void {
    void change(() => api.pauseReminders(hours, newIdempotencyKey()));
  }

  async function saveDay(input: DayProfileInput): Promise<void> {
    const before = loaded.data;
    if (!before) return;
    await change(async () => {
      const day = await api.saveDayProfile(input, before.view.day.version);
      return { ...(await api.reminders()), day };
    }, en.reminders.daySaved);
  }
</script>

<Screen title={en.reminders.title}>
  {#if failure}<p class="section error" role="alert">{failure}</p>{/if}
  <AsyncView resource={loaded} loadingLabel={en.reminders.loading} skeletonRows={6}>
    {#snippet children(data)}
      {#each data.view.kinds.filter((kind) => kind.available) as kind (kind.kind)}
        <KindPresets
          {kind}
          presets={data.presets.prompts.filter((preset) => preset.kind === kind.kind && preset.available)}
          timeZone={data.view.timeZone}
          {busy}
          onTurnOn={turnOn}
          onTurnOff={turnOff}
        />
      {/each}

      <section class="section" aria-labelledby="rem-pause">
        <h2 class="section-title" id="rem-pause">
          {data.view.pausedUntil ? en.reminders.pausedUntil(dayAndClockIn(data.view.timeZone, data.view.pausedUntil)) : en.reminders.title}
        </h2>
        <div class="section-body pad row">
          {#if data.view.pausedUntil}
            <button class="btn btn-tinted" type="button" disabled={busy} onclick={() => pause(0)}>{en.reminders.resume}</button>
          {:else}
            {#each PAUSES as hours (hours)}
              <button class="btn btn-tinted" type="button" disabled={busy} onclick={() => pause(hours)}>{en.reminders.pause(hours)}</button>
            {/each}
          {/if}
          <p class="muted">{en.reminders.pauseNote}</p>
        </div>
      </section>

      <section class="section" aria-labelledby="rem-tomorrow">
        <h2 class="section-title" id="rem-tomorrow">{en.reminders.tomorrow}</h2>
        <ol class="section-body pad timeline">
          {#each data.timeline.entries as entry (`${entry.at}-${entry.kind}`)}
            <li><span class="numeric">{clockIn(data.view.timeZone, entry.at)}</span> {entry.name}</li>
          {:else}
            <li class="muted">{en.reminders.tomorrowEmpty}</li>
          {/each}
        </ol>
      </section>

      <div class="section section-body">
        <LinkRow path="/meds" label={en.reminders.medication(data.view.medicationSchedules)} />
      </div>

      {#key data.view.day.version}
        <DayForm day={data.view.day} {busy} onSave={(input) => void saveDay(input)} />
      {/key}
    {/snippet}
  </AsyncView>
</Screen>

<style>
  .row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--dg-space-3);
  }

  .timeline {
    display: grid;
    gap: var(--dg-space-2);
    margin: 0;
    list-style: none;
  }

  .error {
    color: var(--dg-danger);
    font-weight: 600;
  }
</style>
