<script lang="ts">
  import type { MedicationOptions } from '../../api/types';
  import type { MedicationField, MedicationForm } from '../../forms/medication-rules';
  import { parseDecimal } from '../../forms/decimal';
  import { t as en } from '../../i18n/index.svelte';
  import Field from '../../ui/Field.svelte';

  interface Props {
    form: MedicationForm;
    options: MedicationOptions;
    errors: Partial<Record<MedicationField, string>>;
    onStep: () => void;
  }

  let { form = $bindable(), options, errors, onStep }: Props = $props();

  const DOSE_STEP = 0.25;

  /** The stepper only moves the number the person entered; it proposes nothing. */
  function step(direction: 1 | -1): void {
    const current = Number(parseDecimal(form.doseAmount, { integerDigits: 4, fractionDigits: 3 }) ?? '0');
    form.doseAmount = String(Math.max(DOSE_STEP, current + direction * DOSE_STEP));
    onStep();
  }
</script>

<div class="section-body pad fields">
  <Field id="med-name" label={en.editor.name} required error={errors.name}>
    {#snippet children(attributes)}
      <!-- No suggestions from any catalogue, and none kept by the WebView (MA-EDIT-3). -->
      <input
        {...attributes}
        class="input"
        type="text"
        autocomplete="off"
        autocapitalize="words"
        spellcheck="false"
        bind:value={form.name}
      />
    {/snippet}
  </Field>

  <!-- A field is offered only when the server lists choices for it. -->
  {#if options.forms.length > 0}
    <Field id="med-form" label={en.editor.form}>
      {#snippet children(attributes)}
        <select {...attributes} class="input" bind:value={form.form}>
          {#each options.forms as option (option.value)}
            <option value={option.value}>{option.label}</option>
          {/each}
        </select>
      {/snippet}
    </Field>
  {/if}

  {#if options.strengthUnits.length > 0}
  <div class="pair">
    <Field id="med-strengthValue" label={en.editor.strength} optional error={errors.strengthValue}>
      {#snippet children(attributes)}
        <input {...attributes} class="input numeric" type="text" inputmode="decimal" autocomplete="off" bind:value={form.strengthValue} />
      {/snippet}
    </Field>
    <Field id="med-strengthUnit" label={en.editor.strengthUnit} error={errors.strengthUnit}>
      {#snippet children(attributes)}
        <select {...attributes} class="input" bind:value={form.strengthUnit}>
          <option value="">{en.editor.chooseUnit}</option>
          {#each options.strengthUnits as option (option.value)}
            <option value={option.value}>{option.label}</option>
          {/each}
        </select>
      {/snippet}
    </Field>
  </div>
  {/if}

  <Field id="med-doseAmount" label={en.editor.dose} required error={errors.doseAmount}>
    {#snippet children(attributes)}
      <div class="stepper">
        <button type="button" class="btn btn-tinted" aria-label={en.editor.less} onclick={() => step(-1)}>
          <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path d="M6 12h12" /></svg>
        </button>
        <input {...attributes} class="input numeric" type="text" inputmode="decimal" autocomplete="off" bind:value={form.doseAmount} />
        <button type="button" class="btn btn-tinted" aria-label={en.editor.more} onclick={() => step(1)}>
          <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path d="M6 12h12M12 6v12" /></svg>
        </button>
      </div>
    {/snippet}
  </Field>

  <Field id="med-doseUnit" label={en.editor.doseUnit}>
    {#snippet children(attributes)}
      <select {...attributes} class="input" bind:value={form.doseUnit}>
        {#each options.doseUnits as option (option.value)}
          <option value={option.value}>{option.label}</option>
        {/each}
      </select>
    {/snippet}
  </Field>
</div>

<style>
  .fields {
    display: grid;
    gap: var(--dg-space-4);
  }

  .pair {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(8rem, 1fr));
    gap: var(--dg-space-3);
    align-items: start;
  }

  .stepper {
    display: flex;
    gap: var(--dg-space-2);
  }

  svg {
    fill: none;
    stroke: currentColor;
    stroke-width: 2;
    stroke-linecap: round;
  }
</style>
