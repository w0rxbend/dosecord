<script lang="ts">
  import { tick } from 'svelte';
  import { toApiError, type ApiError, type FieldError } from '../../api/client';
  import type { Snippet } from 'svelte';
  import type { Medication, MedicationOptions } from '../../api/types';
  import { useApp } from '../../app/context';
  import { useLeaveGuard, useMainButton } from '../../app/main-button.svelte';
  import {
    emptyMedicationForm,
    toMedicationDraft,
    toMedicationForm,
    validateMedication,
    type MedicationField,
  } from '../../forms/medication-rules';
  import { t as en, fieldErrorText } from '../../i18n/index.svelte';
  import ErrorState from '../../ui/ErrorState.svelte';
  import ErrorSummary from '../../ui/ErrorSummary.svelte';
  import MedFields from './MedFields.svelte';
  import MedLooks from './MedLooks.svelte';

  interface Props {
    options: MedicationOptions;
    medication: Medication | null;
    /** Shown after the form, before the notice: the times of an existing medication. */
    after?: Snippet;
  }

  let { options, medication, after }: Props = $props();

  const { api, tg, router, toasts } = useApp();
  const LABELS: Partial<Record<MedicationField, string>> = {
    name: en.editor.name,
    strengthValue: en.editor.strength,
    strengthUnit: en.editor.strengthUnit,
    doseAmount: en.editor.dose,
    notes: en.editor.notes,
  };
  const controlId = (field: string): string => `med-${field}`;
  const startingForm = () => (medication ? toMedicationForm(medication) : emptyMedicationForm());

  let form = $state(startingForm());
  let saved = $state(JSON.stringify(startingForm()));
  // A field shows errors once it has lost focus, then on every change (7.4 behaviour 1).
  let visited = $state<string[]>([]);
  let attempted = $state(false);
  let serverErrors = $state<readonly FieldError[]>([]);
  let saveError = $state<ApiError | null>(null);
  let saving = $state(false);
  let announcement = $state('');

  const dirty = $derived(JSON.stringify(form) !== saved);
  const clientErrors = $derived(validateMedication(form, options.limits));
  const shown = $derived([
    ...clientErrors.filter((error) => attempted || visited.includes(error.field)),
    ...serverErrors,
  ]);
  const messages = $derived(
    Object.fromEntries(shown.map((error) => [error.field, fieldErrorText(error.code)])) as Partial<
      Record<MedicationField, string>
    >,
  );
  const summary = $derived(
    shown.map((error) => ({
      controlId: controlId(error.field),
      label: LABELS[error.field as MedicationField] ?? error.field,
      message: fieldErrorText(error.code),
    })),
  );
  const doseChanged = $derived(medication !== null && form.doseAmount.trim() !== medication.doseAmount);

  // The button is active for any changed form, valid or not, so that pressing it shows what
  // is missing (MA-EDIT-5).
  useMainButton(tg, { text: en.editor.save, enabled: () => dirty, busy: () => saving, onPress: () => void save() });
  useLeaveGuard(tg, (guard) => router.setGuard(guard), () => dirty, en.common.leave);

  const fieldOf = (event: Event): string => (event.target as HTMLElement).id.replace(/^med-/, '');

  function onFieldLeft(event: FocusEvent): void {
    const field = fieldOf(event);
    if (!visited.includes(field)) visited = [...visited, field];
    const error = clientErrors.find((candidate) => candidate.field === field);
    if (error) announcement = fieldErrorText(error.code);
  }

  function onFieldChanged(field: string): void {
    serverErrors = serverErrors.filter((error) => error.field !== field);
    announcement = '';
  }

  async function rejectWith(errors: readonly FieldError[]): Promise<void> {
    tg.haptic('error');
    await tick();
    document.getElementById(controlId(errors[0]!.field))?.focus();
  }

  async function save(): Promise<void> {
    // The progress state reaches the native button a moment later; a second press must not
    // send a second request (MA-NATIVE-1).
    if (saving) return;
    attempted = true;
    saveError = null;
    if (clientErrors.length > 0) return rejectWith(clientErrors);
    saving = true;
    try {
      const draft = toMedicationDraft(form);
      const result = medication
        ? await api.updateMedication(medication.id, draft, medication.version)
        : await api.createMedication(draft);
      saved = JSON.stringify(form);
      await tick();
      tg.haptic('success');
      toasts.show(en.editor.saved);
      // A new medication continues to its schedule; an edit returns to the list (behaviour 6).
      await router.replace(medication ? '/meds' : `/meds/${result.id}/schedules/new`);
    } catch (cause) {
      const error = toApiError(cause);
      if (error.code === 'validation' && error.fields.length > 0) {
        serverErrors = error.fields;
        await rejectWith(error.fields);
      } else {
        saveError = error;
        tg.haptic('error');
      }
    } finally {
      saving = false;
    }
  }
</script>

{#if attempted && summary.length > 0}
  <ErrorSummary title={en.editor.summaryTitle(summary.length)} entries={summary} />
{/if}
{#if saveError}
  <div class="section"><ErrorState error={saveError} onRetry={() => void save()} /></div>
{/if}

<form novalidate onsubmit={(event) => event.preventDefault()} onfocusout={onFieldLeft} oninput={(event) => onFieldChanged(fieldOf(event))}>
  <fieldset disabled={saving}>
    <div class="section">
      <MedFields bind:form {options} errors={messages} onStep={() => onFieldChanged('doseAmount')} />
      {#if doseChanged}<p class="note">{en.editor.doseChangeNote}</p>{/if}
    </div>
    <MedLooks bind:form {options} errors={messages} />
  </fieldset>
</form>

{@render after?.()}

<!-- Always present on the editor (MA-EDIT-6). -->
<p class="note">{en.editor.disclaimer}</p>
<p class="visually-hidden" role="status">{announcement}</p>

<style>
  .note {
    margin-block: var(--dg-space-2) var(--dg-space-5);
  }
</style>
