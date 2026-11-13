<script lang="ts">
  import { toApiError } from '../../api/client';
  import type { SleepSettings } from '../../api/types';
  import { useApp } from '../../app/context';
  import { en, errorText } from '../../i18n/en';
  import Field from '../../ui/Field.svelte';

  let { onSaved }: { onSaved: () => void } = $props();
  const { api, tg, toasts } = useApp();

  let settings = $state<SleepSettings | null>(null);
  let useGoal = $state(false);
  let hours = $state('7');
  let minutes = $state('30');
  let error = $state<string | undefined>(undefined);

  $effect(() => {
    void api.sleepSettings().then((read) => {
      settings = read;
      useGoal = read.goalMinutes !== undefined;
      if (read.goalMinutes !== undefined) {
        hours = String(Math.floor(read.goalMinutes / 60));
        minutes = String(read.goalMinutes % 60);
      }
    });
  });

  async function submit(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (!settings) return;
    const total = Number(hours) * 60 + Number(minutes);
    const { goalMinMinutes, goalMaxMinutes } = settings.limits;
    if (useGoal && (!Number.isInteger(total) || total < goalMinMinutes || total > goalMaxMinutes)) {
      error = en.fieldErrors.out_of_range;
      return;
    }
    error = undefined;
    try {
      settings = await api.saveSleepSettings(useGoal ? total : undefined, settings.version);
      tg.haptic('success');
      toasts.show(en.sleep.goalSaved);
      onSaved();
    } catch (cause) {
      tg.haptic('error');
      toasts.show(errorText(toApiError(cause).code));
    }
  }
</script>

<!-- The user's own number; no reference duration is offered (MA-SLEEP-4). -->
<form class="form" onsubmit={submit} novalidate aria-label={en.sleep.goal}>
  <label class="toggle"><input type="checkbox" bind:checked={useGoal} /> {en.sleep.useGoal}</label>
  {#if useGoal}
    <p class="muted">{en.sleep.goalHelp}</p>
    <div class="pair">
      <Field id="sleep-goal-hours" label={en.sleep.goalHours} {error}>
        {#snippet children(attributes)}<input {...attributes} class="input numeric" inputmode="numeric" bind:value={hours} />{/snippet}
      </Field>
      <Field id="sleep-goal-minutes" label={en.sleep.goalMinutes}>
        {#snippet children(attributes)}<input {...attributes} class="input numeric" inputmode="numeric" bind:value={minutes} />{/snippet}
      </Field>
    </div>
  {/if}
  <button type="submit" class="btn btn-tinted" disabled={settings === null}>{en.sleep.saveGoal}</button>
</form>

<style>
  .form {
    display: grid;
    gap: var(--dg-space-3);
    justify-items: start;
  }

  .toggle {
    display: flex;
    align-items: center;
    gap: var(--dg-space-2);
    min-block-size: var(--dg-target);
  }

  .pair {
    display: grid;
    grid-template-columns: repeat(2, minmax(6rem, 1fr));
    gap: var(--dg-space-3);
  }
</style>
