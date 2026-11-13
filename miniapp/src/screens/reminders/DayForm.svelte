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
  const TIMES: readonly TimeField[] = ['wake', 'breakfast', 'lunch', 'dinner', 'bedtime', 'workStart', 'workEnd', 'quietStart', 'quietEnd'];

  type Draft = { -readonly [K in keyof DayProfileInput]: DayProfileInput[K] } & { workDays: number[] };

  let { day, busy, onSave }: Props = $props();
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
  <h2 class="section-title" id="day-title">{en.reminders.day}</h2>
  <div class="section-body pad form">
    <div class="times">
      {#each TIMES as field (field)}
        <Field id={`day-${field}`} label={en.reminders[field]} error={field === 'workStart' && order ? en.reminders.workOrder : undefined}>
          {#snippet children(attributes)}
            <input {...attributes} class="input numeric" type="time" bind:value={draft[field]} disabled={busy} />
          {/snippet}
        </Field>
      {/each}
    </div>
    <fieldset class="days">
      <legend>{en.reminders.workDays}</legend>
      {#each en.reminders.weekdays as label, index (label)}
        <label class="chip">
          <input type="checkbox" checked={draft.workDays.includes(index + 1)} disabled={busy} onchange={(event) => toggleDay(index + 1, event.currentTarget.checked)} />
          <span>{label}</span>
        </label>
      {/each}
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

  .days {
    display: flex;
    flex-wrap: wrap;
    gap: var(--dg-space-2);
    border: 0;
    padding: 0;
  }
</style>
