<script lang="ts">
  import type { ScheduleOptions } from '../../api/types';
  import { groupRows, type DayGroupForm } from '../../forms/schedule-rules';
  import { t as en } from '../../i18n/index.svelte';
  import TimeRows from './TimeRows.svelte';

  /** Groups of days, each with its own times: weekdays at 08:00, the weekend at 10:00 (ROADMAP G9.8, G9.9). */
  interface Props {
    groups: DayGroupForm[];
    options: ScheduleOptions;
    doseUnit: string;
    errors: Readonly<Record<string, string>>;
    /** The dose a new row starts from: the medication's. */
    firstDose: string;
  }

  let { groups = $bindable(), options, doseUnit, errors, firstDose }: Props = $props();

  const GROUPS_MAX = 7;
  let nextKey = 2000;

  function addGroupRow(group: DayGroupForm): void {
    group.rows.push({ key: nextKey++, time: '', dose: group.rows.at(-1)?.dose ?? firstDose });
  }

  function removeGroupRow(group: DayGroupForm, key: number): void {
    group.rows = group.rows.filter((row) => row.key !== key);
  }

  function addGroup(): void {
    groups = [...groups, { key: nextKey++, weekdays: [], rows: [{ key: nextKey++, time: '', dose: firstDose }] }];
  }

  function removeGroup(key: number): void {
    groups = groups.filter((group) => group.key !== key);
  }
</script>

<section class="section" aria-labelledby="groups-title">
  <h2 class="section-title" id="groups-title">{en.schedule.groups}</h2>
  {#if errors.groups}<p class="error pad" role="alert">{errors.groups}</p>{/if}
  {#each groups as group, index (group.key)}
    <fieldset class="section-body pad stack group" aria-describedby={errors[`groups.${index}.weekdays`] ? `sch-groups-${index}-error` : undefined}>
      <legend class="group-title">{en.schedule.group(index + 1)}</legend>
      <div class="chips">
        {#each options.weekdays as day (day.value)}
          <label class="chip">
            <input type="checkbox" name="sch-groups.{index}.weekdays" value={day.value} bind:group={group.weekdays} />
            <span>{day.label}</span>
          </label>
        {/each}
      </div>
      {#if errors[`groups.${index}.weekdays`]}
        <p class="error" id="sch-groups-{index}-error" role="alert">{errors[`groups.${index}.weekdays`]}</p>
      {/if}
      <TimeRows
        bind:rows={group.rows}
        {doseUnit}
        perTimeDose={options.perTimeDose}
        maxRows={options.maxRows}
        {errors}
        prefix={groupRows(index)}
        onAdd={() => addGroupRow(group)}
        onRemove={(key) => removeGroupRow(group, key)}
      />
      {#if groups.length > 1}
        <button type="button" class="btn" onclick={() => removeGroup(group.key)}>{en.schedule.removeGroup(index + 1)}</button>
      {/if}
    </fieldset>
  {/each}
  <div class="section-body pad">
    <button type="button" class="btn btn-tinted" disabled={groups.length >= GROUPS_MAX} onclick={addGroup}>{en.schedule.addGroup}</button>
  </div>
</section>

<style>
  .stack {
    display: grid;
    gap: var(--dg-space-4);
  }

  .group-title {
    font-weight: 600;
  }

  .error {
    color: var(--dg-danger);
    font-size: var(--dg-text-secondary);
    font-weight: 600;
  }
</style>
