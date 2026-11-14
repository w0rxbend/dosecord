<script lang="ts">
  import { toApiError } from '../../api/client';
  import type { MealKind, NutritionSettings } from '../../api/types';
  import { useApp } from '../../app/context';
  import { t as en, errorText } from '../../i18n/index.svelte';
  import { MAIN_MEALS } from './meal';

  let { onSaved }: { onSaved: () => void } = $props();
  const { api, tg, toasts } = useApp();

  let settings = $state<NutritionSettings | null>(null);
  let planned = $state<MealKind[]>([]);

  $effect(() => {
    void api.nutritionSettings().then((read) => {
      settings = read;
      planned = [...read.planned];
    });
  });

  async function submit(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    if (!settings) return;
    try {
      settings = await api.saveNutritionSettings(planned, settings.version);
      tg.haptic('success');
      toasts.show(en.food.plannedSaved);
      onSaved();
    } catch (cause) {
      tg.haptic('error');
      toasts.show(errorText(toApiError(cause).code));
    }
  }
</script>

<!-- The person's own plan; none is required, and a day without one is a count (VISUALS 7.5). -->
<form class="form" onsubmit={submit} aria-label={en.food.planned}>
  <p class="muted">{en.food.plannedHelp}</p>
  <fieldset class="meals">
    <legend class="visually-hidden">{en.food.planned}</legend>
    {#each MAIN_MEALS as meal (meal)}
      <label class="meal"><input type="checkbox" value={meal} bind:group={planned} /> {en.food.kinds[meal]}</label>
    {/each}
  </fieldset>
  <button type="submit" class="btn btn-tinted" disabled={settings === null}>{en.food.savePlanned}</button>
</form>

<style>
  .form {
    display: grid;
    gap: var(--dg-space-3);
    justify-items: start;
  }

  .meals {
    display: flex;
    flex-wrap: wrap;
    gap: var(--dg-space-4);
    border: 0;
    padding: 0;
    margin: 0;
  }

  .meal {
    display: flex;
    align-items: center;
    gap: var(--dg-space-2);
    min-block-size: var(--dg-target);
  }
</style>
