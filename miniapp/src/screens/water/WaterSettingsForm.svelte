<script lang="ts">
  import { tick, untrack } from 'svelte';
  import { toApiError } from '../../api/client';
  import type { WaterSettings } from '../../api/types';
  import { useApp } from '../../app/context';
  import { useLeaveGuard, useMainButton } from '../../app/main-button.svelte';
  import { t as en, errorText } from '../../i18n/index.svelte';
  import Field from '../../ui/Field.svelte';

  let { settings }: { settings: WaterSettings } = $props();

  const { api, tg, router, toasts } = useApp();
  const initial = untrack(() => settings);
  const { limits } = initial;

  // The goal is typed by the person; there is no calculation from weight or activity (MINI_APP 7.7).
  let hasGoal = $state(initial.goalMl !== undefined);
  let goal = $state(String(initial.goalMl ?? ''));
  let presets = $state(Array.from({ length: limits.presetsMax }, (_, index) => String(initial.presetsMl[index] ?? '')));
  let version = $state(initial.version);
  let saving = $state(false);
  let attempted = $state(false);
  let failure = $state<string | undefined>(undefined);
  let savedForm = $state(
    JSON.stringify({
      hasGoal: initial.goalMl !== undefined,
      goal: String(initial.goalMl ?? ''),
      presets: Array.from({ length: limits.presetsMax }, (_, index) => String(initial.presetsMl[index] ?? '')),
    }),
  );

  const whole = (text: string): number | null => (/^\d+$/.test(text.trim()) ? Number(text.trim()) : null);
  const amounts = $derived(presets.map(whole).filter((value) => value !== null));
  const goalError = $derived.by(() => {
    const value = whole(goal);
    return hasGoal && (value === null || value < limits.goalMinMl || value > limits.goalMaxMl)
      ? en.waterSettings.goalInvalid(limits.goalMinMl, limits.goalMaxMl)
      : undefined;
  });
  const presetError = $derived.by(() => {
    const typed = presets.filter((text) => text.trim() !== '');
    const valid =
      typed.length > 0 &&
      typed.length === amounts.length &&
      amounts.every((value) => value >= limits.entryMinMl && value <= limits.entryMaxMl) &&
      new Set(amounts).size === amounts.length;
    return valid ? undefined : en.waterSettings.presetInvalid(limits.entryMinMl, limits.entryMaxMl);
  });
  const dirty = $derived(JSON.stringify({ hasGoal, goal, presets }) !== savedForm);

  useMainButton(tg, { text: en.waterSettings.save, enabled: () => dirty, busy: () => saving, onPress: () => void save() });
  useLeaveGuard(tg, (guard) => router.setGuard(guard), () => dirty, en.common.leave);

  async function save(): Promise<void> {
    attempted = true;
    if (goalError || presetError) {
      tg.haptic('error');
      return;
    }
    saving = true;
    failure = undefined;
    try {
      const saved = await api.saveWaterSettings({ presetsMl: amounts, goalMl: hasGoal ? (whole(goal) ?? undefined) : undefined }, version);
      version = saved.version;
      savedForm = JSON.stringify({ hasGoal, goal, presets });
      toasts.show(en.waterSettings.saved);
      tg.haptic('success');
      // The leave guard follows the form on the next tick; leave once it knows nothing is unsaved.
      await tick();
      await router.back();
    } catch (cause) {
      failure = errorText(toApiError(cause).code);
      tg.haptic('error');
    } finally {
      saving = false;
    }
  }
</script>

<form class="section section-body pad form" onsubmit={(event) => { event.preventDefault(); void save(); }} novalidate>
  {#if failure}<p class="error" role="alert">{failure}</p>{/if}
  <label class="toggle">
    <input type="checkbox" role="switch" bind:checked={hasGoal} />
    <span>{en.waterSettings.useGoal}</span>
  </label>
  {#if hasGoal}
    <Field id="water-goal" label={en.waterSettings.goal} help={en.waterSettings.goalHelp} error={attempted ? goalError : undefined}>
      {#snippet children(attributes)}
        <input {...attributes} class="input numeric" inputmode="numeric" autocomplete="off" bind:value={goal} disabled={saving} />
      {/snippet}
    </Field>
  {:else}
    <p class="muted">{en.waterSettings.goalHelp}</p>
  {/if}
  <fieldset class="presets">
    <legend>{en.waterSettings.presets}</legend>
    <p class="muted">{en.waterSettings.presetsHelp(limits.presetsMax)}</p>
    {#each presets as _, index (index)}
      <label class="visually-hidden" for="water-preset-{index}">{en.waterSettings.preset(index + 1)}</label>
      <input
        id="water-preset-{index}"
        class="input numeric"
        inputmode="numeric"
        autocomplete="off"
        aria-invalid={attempted && presetError ? 'true' : undefined}
        aria-describedby={attempted && presetError ? 'water-presets-error' : undefined}
        bind:value={presets[index]}
        disabled={saving}
      />
    {/each}
    {#if attempted && presetError}<p class="error" id="water-presets-error">{presetError}</p>{/if}
  </fieldset>
</form>

<style>
  .form {
    display: grid;
    gap: var(--dg-space-4);
  }

  .toggle {
    display: flex;
    align-items: center;
    gap: var(--dg-space-3);
    min-block-size: var(--dg-target);
    font-weight: 600;
  }

  .toggle input {
    inline-size: 24px;
    block-size: 24px;
  }

  .presets {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(5rem, 1fr));
    gap: var(--dg-space-2);
  }

  legend,
  .presets > p {
    grid-column: 1 / -1;
  }

  legend {
    font-weight: 600;
    margin-block-end: var(--dg-space-1);
  }

  .error {
    grid-column: 1 / -1;
    color: var(--dg-danger);
    font-weight: 600;
  }
</style>
