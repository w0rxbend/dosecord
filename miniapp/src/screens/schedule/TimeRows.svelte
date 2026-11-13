<script lang="ts">
  import { rowField, type ScheduleRowForm } from '../../forms/schedule-rules';
  import { en } from '../../i18n/en';
  import Field from '../../ui/Field.svelte';

  interface Props {
    rows: ScheduleRowForm[];
    doseUnit: string;
    perTimeDose: boolean;
    maxRows: number;
    errors: Readonly<Record<string, string>>;
    onAdd: () => void;
    onRemove: (key: number) => void;
  }

  let { rows = $bindable(), doseUnit, perTimeDose, maxRows, errors, onAdd, onRemove }: Props = $props();
</script>

<!-- Every time and dose is typed by the person; nothing is proposed (MA-SCHED-10). -->
<ul class="rows">
  {#each rows as row, index (row.key)}
    <li class="time-row">
      <Field id="sch-{rowField(index, 'time')}" label={en.schedule.time(index + 1)} required error={errors[rowField(index, 'time')]}>
        {#snippet children(attributes)}
          <input {...attributes} class="input numeric" type="time" bind:value={row.time} />
        {/snippet}
      </Field>
      {#if perTimeDose}
        <Field id="sch-{rowField(index, 'dose')}" label={en.schedule.dose(index + 1)} help={doseUnit} required error={errors[rowField(index, 'dose')]}>
          {#snippet children(attributes)}
            <input {...attributes} class="input numeric" type="text" inputmode="decimal" autocomplete="off" bind:value={row.dose} />
          {/snippet}
        </Field>
      {/if}
      <button type="button" class="btn" aria-label={en.schedule.remove(index + 1)} onclick={() => onRemove(row.key)}>
        <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
      </button>
    </li>
  {/each}
</ul>
{#if errors.rows}<p class="error" role="alert">{errors.rows}</p>{/if}
<button type="button" class="btn btn-tinted" disabled={rows.length >= maxRows} onclick={onAdd}>
  {en.schedule.addTime}
</button>

<style>
  .rows {
    display: grid;
    gap: var(--dg-space-4);
  }

  .time-row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto;
    gap: var(--dg-space-2);
    align-items: start;
  }

  /* Level with the inputs, below the line their labels take. */
  .time-row .btn {
    margin-block-start: calc(1.4rem + var(--dg-space-1));
  }

  .error {
    color: var(--dg-danger);
    font-size: var(--dg-text-secondary);
    font-weight: 600;
  }

  svg {
    fill: none;
    stroke: currentColor;
    stroke-width: 2;
    stroke-linecap: round;
  }
</style>
