<script lang="ts">
  import { untrack } from 'svelte';
  import type { DayProfile, DayProfileInput } from '../../api/types';
  import { en } from '../../i18n/en';
  import Field from '../../ui/Field.svelte';

  interface Props {
    day: DayProfile;
    busy: boolean;
    onSave: (input: DayProfileInput) => void;
  }

  type TimeField = 'wake' | 'breakfast' | 'lunch' | 'dinner' | 'bedtime' | 'workStart' | 'workEnd' | 'quietStart' | 'quietEnd';
  const MEALS: readonly TimeField[] = ['wake', 'breakfast', 'lunch', 'dinner', 'bedtime'];
  const WORK: readonly TimeField[] = ['workStart', 'workEnd'];
  const QUIET: readonly TimeField[] = ['quietStart', 'quietEnd'];

  type Draft = { -readonly [K in keyof DayProfileInput]: DayProfileInput[K] } & { workDays: number[] };

  let { day, busy, onSave, heading = true }: Props & { heading?: boolean } = $props();
  // The form edits a copy of the day it was opened with; the screen opens a new form for a saved day.
  let draft = $state<Draft>(untrack(() => ({ ...day, workDays: [...day.workDays] })));
  const order = $derived(draft.workStart >= draft.workEnd);

  function toggleDay(day: number, on: boolean): void {
    const days = new Set(draft.workDays);
    if (on) days.add(day);
    else days.delete(day);
    draft.workDays = [...days].sort((a, b) => a - b);
  }
</script>

<form
  class="section"
  aria-labelledby="day-title"
  onsubmit={(event) => {
    event.preventDefault();
    if (!order && draft.workDays.length > 0) onSave(draft);
  }}
>
  <!-- On its own page the screen title names the form; the heading stays for assistive technology. -->
  <h2 class="section-title" class:visually-hidden={!heading} id="day-title">{en.reminders.day}</h2>
  <div class="section-body pad form">
    {#snippet times(fields: readonly TimeField[])}
      <div class="times">
        {#each fields as field (field)}
          <Field id={`day-${field}`} label={en.reminders[field]} error={field === 'workStart' && order ? en.reminders.workOrder : undefined}>
            {#snippet children(attributes)}
              <input {...attributes} class="input numeric" type="time" bind:value={draft[field]} disabled={busy} />
            {/snippet}
          </Field>
        {/each}
      </div>
    {/snippet}
    <fieldset class="group">
      <legend>{en.reminders.groupMeals}</legend>
      {@render times(MEALS)}
    </fieldset>
    <fieldset class="group">
      <legend>{en.reminders.groupWork}</legend>
      {@render times(WORK)}
      <fieldset class="days">
        <legend class="visually-hidden">{en.reminders.workDays}</legend>
        <span class="muted" aria-hidden="true">{en.reminders.workDays}</span>
        <div class="chips">
          {#each en.reminders.weekdays as label, index (label)}
            <label class="chip">
              <input type="checkbox" checked={draft.workDays.includes(index + 1)} disabled={busy} onchange={(event) => toggleDay(index + 1, event.currentTarget.checked)} />
              <span>{label}</span>
            </label>
          {/each}
        </div>
      </fieldset>
    </fieldset>
    <fieldset class="group">
      <legend>{en.reminders.groupQuiet}</legend>
      {@render times(QUIET)}
    </fieldset>
    <p class="muted">{en.reminders.dayHelp}</p>
    <button class="btn btn-filled" type="submit" disabled={busy || order || draft.workDays.length === 0}>{en.reminders.saveDay}</button>
  </div>
</form>

<style>
  .form {
    display: grid;
    gap: var(--dg-space-4);
  }

  .times {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: var(--dg-space-3);
  }

  .group {
    display: grid;
    gap: var(--dg-space-3);
    padding-block-start: var(--dg-space-3);
    border-block-start: 1px solid var(--dg-separator);
  }

  .group:first-of-type {
    padding-block-start: 0;
    border-block-start: 0;
  }

  legend {
    padding: 0;
    font-weight: 600;
  }

  /* A group's legend reads as a sub-heading, a step quieter than the field labels under it. */
  .group > legend {
    float: inline-start;
    inline-size: 100%;
    color: var(--dg-text-section);
    font-size: var(--dg-text-secondary);
  }

  .days {
    display: grid;
    gap: var(--dg-space-2);
    border: 0;
    padding: 0;
  }

  .chips {
    gap: var(--dg-space-1);
  }

  .chip span {
    padding: 0 var(--dg-space-3);
  }
</style>
