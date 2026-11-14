<script lang="ts">
  import { untrack } from 'svelte';
  import type { FieldError } from '../../api/client';
  import type { MealKind } from '../../api/types';
  import { useApp } from '../../app/context';
  import { en, fieldErrorText } from '../../i18n/en';
  import Field from '../../ui/Field.svelte';
  import { MEAL_KINDS, type MealDraft } from './meal';

  interface Props {
    draft: MealDraft;
    busy: boolean;
    errors: readonly FieldError[];
    onSave: (draft: MealDraft) => void;
    onCancel: () => void;
  }

  let { draft, busy, errors, onSave, onCancel }: Props = $props();
  const { api } = useApp();

  // The sheet starts from the draft it was opened with and is the person's from then on.
  const initial = untrack(() => draft);
  let mealType = $state<MealKind>(initial.mealType);
  let time = $state(initial.time);
  let description = $state(initial.description);
  let recent = $state<readonly string[]>([]);

  // "Recent" holds up to five descriptions saved before for the chosen meal type (MINI_APP 7.8 behaviour 2).
  $effect(() => {
    const kind = mealType;
    void api.recentMeals(kind).then(
      (texts) => {
        if (kind === mealType) recent = texts;
      },
      () => (recent = []),
    );
  });

  const errorOf = (field: string): string | undefined => {
    const found = errors.find((error) => error.field === field);
    return found && fieldErrorText(found.code);
  };

  function submit(event: SubmitEvent): void {
    event.preventDefault();
    onSave({ ...initial, mealType, time, description, atNow: initial.atNow && time === initial.time });
  }
</script>

<!-- The meal sheet of MINI_APP 7.8 as an in-page form: a type and a time are enough (MA-FOOD-3). -->
<form class="form" onsubmit={submit} novalidate aria-label={initial.replaces ? en.food.edit : en.food.add}>
  <fieldset class="kinds">
    <legend>{en.food.mealType}</legend>
    {#each MEAL_KINDS as option (option)}
      <label class="kind"><input type="radio" name="meal-type" value={option} bind:group={mealType} /> {en.food.kinds[option]}</label>
    {/each}
  </fieldset>
  <Field id="meal-time" label={en.food.time} error={errorOf('occurredAt')}>
    {#snippet children(attributes)}<input {...attributes} class="input numeric" type="time" bind:value={time} />{/snippet}
  </Field>
  <Field id="meal-description" label={en.food.description} error={errorOf('description')}>
    {#snippet children(attributes)}
      <textarea {...attributes} class="input" rows="2" maxlength="300" bind:value={description}></textarea>
    {/snippet}
  </Field>
  {#if recent.length > 0}
    <div class="recent" role="group" aria-label={en.food.recent}>
      <span class="muted">{en.food.recent}:</span>
      {#each recent as text (text)}
        <button type="button" class="btn btn-tinted chip" onclick={() => (description = text)}>{text}</button>
      {/each}
    </div>
  {/if}
  <div class="buttons">
    <button type="submit" class="btn btn-filled" disabled={busy}>{en.food.save}</button>
    <button type="button" class="btn" onclick={onCancel}>{en.food.cancel}</button>
  </div>
</form>

<style>
  .form {
    display: grid;
    gap: var(--dg-space-3);
    justify-items: start;
  }

  .kinds {
    display: flex;
    flex-wrap: wrap;
    gap: var(--dg-space-3);
    border: 0;
    padding: 0;
    margin: 0;
  }

  .kind {
    display: flex;
    align-items: center;
    gap: var(--dg-space-2);
    min-block-size: var(--dg-target);
  }

  textarea {
    inline-size: 100%;
  }

  .recent,
  .buttons {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--dg-space-2);
  }
</style>
