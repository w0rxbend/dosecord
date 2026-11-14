<script lang="ts">
  import type { ScheduleOptions } from '../../api/types';
  import type { ScheduleForm } from '../../forms/schedule-rules';
  import { t as en } from '../../i18n/index.svelte';
  import Field from '../../ui/Field.svelte';
  import TimeRows from './TimeRows.svelte';

  interface Props {
    form: ScheduleForm;
    options: ScheduleOptions;
    doseUnit: string;
    errors: Readonly<Record<string, string>>;
  }

  let { form = $bindable(), options, doseUnit, errors }: Props = $props();

  const timed = $derived(form.pattern !== 'as_needed');
  let nextKey = 1;

  function addRow(): void {
    form.rows.push({ key: nextKey++, time: '', dose: form.rows.at(-1)?.dose ?? '' });
  }

  function removeRow(key: number): void {
    form.rows = form.rows.filter((row) => row.key !== key);
  }
</script>

<!-- One scrolling form, not a wizard: the preview stays one scroll away from any field. The
     patterns offered are exactly those the server lists as enabled (MA-SCHED-8). -->
<fieldset class="section">
  <legend class="section-title">{en.schedule.pattern}</legend>
  <div class="section-body">
    {#each options.patterns as option (option.value)}
      <label class="choice">
        <input type="radio" name="sch-pattern" value={option.value} bind:group={form.pattern} />
        {option.label}
      </label>
    {/each}
  </div>
</fieldset>

{#if form.pattern === 'weekly'}
  <fieldset class="section" aria-describedby={errors.weekdays ? 'sch-weekdays-error' : undefined}>
    <legend class="section-title">{en.schedule.weekdays}</legend>
    <div class="section-body pad">
      <div class="chips">
        {#each options.weekdays as day (day.value)}
          <label class="chip">
            <input type="checkbox" name="sch-weekdays" value={day.value} bind:group={form.weekdays} />
            <span>{day.label}</span>
          </label>
        {/each}
      </div>
      {#if errors.weekdays}<p class="error" id="sch-weekdays-error" role="alert">{errors.weekdays}</p>{/if}
    </div>
  </fieldset>
{/if}

{#if timed}
  <section class="section" aria-labelledby="times-title">
    <h2 class="section-title" id="times-title">{en.schedule.times}</h2>
    <div class="section-body pad stack">
      <TimeRows bind:rows={form.rows} {doseUnit} perTimeDose={options.perTimeDose} maxRows={options.maxRows} {errors} onAdd={addRow} onRemove={removeRow} />
    </div>
  </section>

  <section class="section" aria-labelledby="dates-title">
    <h2 class="section-title" id="dates-title">{en.schedule.dates}</h2>
    <div class="section-body pad stack">
      <Field id="sch-startDate" label={en.schedule.starts} required error={errors.startDate}>
        {#snippet children(attributes)}
          <input {...attributes} class="input numeric" type="date" bind:value={form.startDate} />
        {/snippet}
      </Field>
      <Field id="sch-endDate" label={en.schedule.ends} optional help={en.schedule.endsHelp} error={errors.endDate}>
        {#snippet children(attributes)}
          <input {...attributes} class="input numeric" type="date" bind:value={form.endDate} />
        {/snippet}
      </Field>
    </div>
  </section>
{/if}

<style>
  .choice {
    display: flex;
    gap: var(--dg-space-3);
    align-items: center;
    min-block-size: var(--dg-target);
    padding: var(--dg-space-2) var(--dg-space-4);
    cursor: pointer;
  }

  .choice input {
    flex: none;
    inline-size: var(--dg-space-5);
    block-size: var(--dg-space-5);
    margin: 0;
    accent-color: var(--dg-accent);
  }

  .stack {
    display: grid;
    gap: var(--dg-space-4);
  }

  .error {
    margin-block-start: var(--dg-space-2);
    color: var(--dg-danger);
    font-size: var(--dg-text-secondary);
    font-weight: 600;
  }
</style>
