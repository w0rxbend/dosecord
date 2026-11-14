<script lang="ts">
  import { untrack } from 'svelte';
  import type { FieldError } from '../../api/client';
  import type { LocalDate, SleepKind } from '../../api/types';
  import { t as en, fieldErrorText } from '../../i18n/index.svelte';
  import Field from '../../ui/Field.svelte';
  import { lastNightDraft, type NightDraft } from './night';

  interface Props {
    today: LocalDate;
    busy: boolean;
    errors: readonly FieldError[];
    onSave: (draft: NightDraft) => void;
  }

  let { today, busy, errors, onSave }: Props = $props();
  // The form starts from last night and is the person's from then on.
  const initial = lastNightDraft(untrack(() => today));
  let kind = $state<SleepKind>(initial.kind);
  let onsetDate = $state(initial.onsetDate);
  let onsetTime = $state(initial.onsetTime);
  let wakeDate = $state(initial.wakeDate);
  let wakeTime = $state(initial.wakeTime);

  const errorOf = (field: string): string | undefined => {
    const found = errors.find((error) => error.field === field);
    return found && fieldErrorText(found.code);
  };

  function submit(event: SubmitEvent): void {
    event.preventDefault();
    onSave({ kind, onsetDate, onsetTime, wakeDate, wakeTime });
  }
</script>

<!-- The night sheet of MINI_APP 7.10 as an in-page form; the server checks every rule again (MA-SLEEP-6). -->
<form class="form" onsubmit={submit} novalidate aria-label={en.sleep.add}>
  <fieldset class="kinds">
    <legend>{en.sleep.kind}</legend>
    {#each ['night', 'nap'] as const as option (option)}
      <label class="kind"><input type="radio" name="sleep-kind" value={option} bind:group={kind} /> {en.sleep.kinds[option]}</label>
    {/each}
  </fieldset>
  <div class="pair">
    <Field id="sleep-onset-date" label={en.sleep.date(en.sleep.fellAsleep)} error={errorOf('onsetAt')}>
      {#snippet children(attributes)}<input {...attributes} class="input numeric" type="date" bind:value={onsetDate} />{/snippet}
    </Field>
    <Field id="sleep-onset-time" label={en.sleep.time(en.sleep.fellAsleep)}>
      {#snippet children(attributes)}<input {...attributes} class="input numeric" type="time" bind:value={onsetTime} />{/snippet}
    </Field>
  </div>
  <div class="pair">
    <Field id="sleep-wake-date" label={en.sleep.date(en.sleep.wokeUp)} error={errorOf('wakeAt')}>
      {#snippet children(attributes)}<input {...attributes} class="input numeric" type="date" bind:value={wakeDate} />{/snippet}
    </Field>
    <Field id="sleep-wake-time" label={en.sleep.time(en.sleep.wokeUp)}>
      {#snippet children(attributes)}<input {...attributes} class="input numeric" type="time" bind:value={wakeTime} />{/snippet}
    </Field>
  </div>
  <button type="submit" class="btn btn-filled" disabled={busy}>{en.sleep.save}</button>
</form>

<style>
  .form {
    display: grid;
    gap: var(--dg-space-3);
    justify-items: start;
  }

  .kinds {
    display: flex;
    gap: var(--dg-space-4);
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

  .pair {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(8rem, 1fr));
    gap: var(--dg-space-3);
    inline-size: 100%;
  }
</style>
