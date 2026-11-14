<script lang="ts">
  import type { MedicationOptions } from '../../api/types';
  import type { MedicationField, MedicationForm } from '../../forms/medication-rules';
  import { t as en } from '../../i18n/index.svelte';
  import Field from '../../ui/Field.svelte';
  import PillTag from '../../ui/PillTag.svelte';

  interface Props {
    form: MedicationForm;
    options: MedicationOptions;
    errors: Partial<Record<MedicationField, string>>;
  }

  let { form = $bindable(), options, errors }: Props = $props();

  // Shape applies to solid forms only (MINI_APP 7.4, shape tag).
  const hasShape = $derived(['tablet', 'capsule', 'other'].includes(form.form));
  const notesLength = $derived([...form.notes].length);
</script>

{#if options.colourTags.length > 0}
<section class="section" aria-labelledby="looks-title">
  <h2 class="section-title" id="looks-title">{en.editor.looks} ({en.common.optional})</h2>
  <div class="section-body pad fields">
    <fieldset>
      <legend>{en.editor.colour}</legend>
      <div class="chips">
        {#each options.colourTags as option (option.value)}
          <label class="chip">
            <input type="radio" name="med-colourTag" value={option.value} bind:group={form.colourTag} />
            <span><PillTag colour={option.value} shape="round" />{option.label}</span>
          </label>
        {/each}
      </div>
    </fieldset>
    {#if hasShape}
      <fieldset>
        <legend>{en.editor.shape}</legend>
        <div class="chips">
          {#each options.shapeTags as option (option.value)}
            <label class="chip">
              <input type="radio" name="med-shapeTag" value={option.value} bind:group={form.shapeTag} />
              <span><PillTag colour="none" shape={option.value} />{option.label}</span>
            </label>
          {/each}
        </div>
      </fieldset>
    {/if}
  </div>
</section>
{/if}

<div class="section section-body pad fields">
  <Field
    id="med-notes"
    label={en.editor.notes}
    optional
    help={en.editor.notesCount(notesLength, options.limits.notesMax)}
    error={errors.notes}
  >
    {#snippet children(attributes)}
      <textarea {...attributes} class="input" autocomplete="off" bind:value={form.notes}></textarea>
    {/snippet}
  </Field>

  {#if options.reminderNameToggle}
  <label class="toggle">
    <input type="checkbox" role="switch" aria-describedby="med-showName-help" bind:checked={form.showNameInReminders} />
    <span>
      {en.editor.showName}
      <span class="muted" id="med-showName-help">{en.editor.showNameHelp}</span>
    </span>
  </label>
  {/if}
</div>

<style>
  .fields {
    display: grid;
    gap: var(--dg-space-4);
  }

  legend {
    margin-block-end: var(--dg-space-2);
    font-weight: 600;
  }

  .toggle {
    display: flex;
    gap: var(--dg-space-3);
    align-items: center;
    min-block-size: var(--dg-target);
    cursor: pointer;
  }

  .toggle input {
    flex: none;
    inline-size: var(--dg-space-6);
    block-size: var(--dg-space-6);
    accent-color: var(--dg-accent);
  }

  .toggle span span {
    display: block;
  }
</style>
