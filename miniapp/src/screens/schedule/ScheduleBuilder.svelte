<script lang="ts">
  import { toApiError } from '../../api/client';
  import type { Medication, Schedule, ScheduleOptions, SchedulePreview } from '../../api/types';
  import { useApp } from '../../app/context';
  import { useLeaveGuard, useMainButton } from '../../app/main-button.svelte';
  import { toScheduleDraft, validateSchedule, type ScheduleForm } from '../../forms/schedule-rules';
  import { en, errorText, fieldErrorText } from '../../i18n/en';
  import PreviewList from './PreviewList.svelte';
  import ScheduleFields from './ScheduleFields.svelte';

  interface Props {
    options: ScheduleOptions;
    medication: Medication;
    /** The schedule to change; absent for a new one. */
    schedule?: Schedule;
  }

  let { options, medication, schedule }: Props = $props();

  const PREVIEW_DELAY_MS = 400;
  const { api, tg, router, toasts } = useApp();

  // The dose is carried over from the medication; the time is the person's to enter (MA-A11Y-16).
  // An edit starts from the schedule as it is.
  const startingForm = (): ScheduleForm =>
    schedule
      ? {
          pattern: schedule.pattern,
          rows: schedule.rows.map((row, key) => ({ key, time: row.time, dose: row.dose || medication.doseAmount })),
          weekdays: [...schedule.weekdays],
          startDate: schedule.startDate,
          endDate: schedule.endDate ?? '',
          everyDays: schedule.everyDays?.toString() ?? '2',
          onDays: schedule.onDays?.toString() ?? '21',
          offDays: schedule.offDays?.toString() ?? '7',
          maxPerDay: schedule.maxPerDay?.toString() ?? '',
          minGapMinutes: schedule.minGapMinutes?.toString() ?? '',
        }
      : {
          pattern: options.patterns[0]?.value ?? 'fixed_times',
          rows: [{ key: 0, time: '', dose: medication.doseAmount }],
          weekdays: [],
          startDate: options.today,
          endDate: '',
          everyDays: '2',
          onDays: '21',
          offDays: '7',
          maxPerDay: '',
          minGapMinutes: '',
        };

  let form = $state(startingForm());
  const initial = JSON.stringify(startingForm());
  let visited = $state<string[]>([]);
  let attempted = $state(false);
  let saving = $state(false);
  let previewed = $state.raw<{ key: string; preview: SchedulePreview } | null>(null);
  let problem = $state<string | null>(null);

  const errors = $derived(validateSchedule(form, options.perTimeDose));
  const draftKey = $derived(errors.length === 0 ? JSON.stringify(toScheduleDraft(form)) : null);
  const shown = $derived(errors.filter((error) => attempted || visited.includes(error.field)));
  const messages = $derived(Object.fromEntries(shown.map((error) => [error.field, fieldErrorText(error.code)])));
  // Save needs a preview of exactly the current draft (MA-SCHED-1).
  const current = $derived(draftKey !== null && previewed?.key === draftKey);

  // A preview is requested 400 ms after the last change to a valid draft (7.5 behaviour 2).
  $effect(() => {
    const key = draftKey;
    if (key === null || previewed?.key === key) return;
    problem = null;
    const timer = setTimeout(async () => {
      try {
        const preview = await api.previewSchedule(medication.id, JSON.parse(key));
        if (key === draftKey) {
          previewed = { key, preview };
          problem = null;
        }
      } catch (cause) {
        if (key !== draftKey) return;
        const error = toApiError(cause);
        const [field] = error.fields;
        problem = field ? fieldErrorText(field.code) : errorText(error.code);
      }
    }, PREVIEW_DELAY_MS);
    return () => clearTimeout(timer);
  });

  const dirty = $derived(JSON.stringify(form) !== initial);
  // An unchanged schedule has nothing to save.
  const savable = $derived(current && (schedule === undefined || dirty));

  useMainButton(tg, { text: en.schedule.save, enabled: () => savable, busy: () => saving, onPress: () => void save() });
  useLeaveGuard(tg, (guard) => router.setGuard(guard), () => !saving && dirty, en.common.leave);

  // A field shows its errors once it was left or its value was committed; a checkbox commits
  // on every tap, so an emptied weekday group says so at once.
  function onFieldLeft(event: Event): void {
    const control = event.target as HTMLInputElement;
    const field = (control.id || control.name || '').replace(/^sch-/, '');
    if (field && !visited.includes(field)) visited = [...visited, field];
  }

  async function save(): Promise<void> {
    if (saving) return;
    attempted = true;
    if (draftKey === null) return tg.haptic('error');
    saving = true;
    try {
      const draft = JSON.parse(draftKey);
      await (schedule
        ? api.reviseSchedule(schedule.id, draft, schedule.revision)
        : api.createSchedule(medication.id, draft));
      tg.haptic('success');
      toasts.show(en.schedule.saved);
      await router.replace('/meds');
    } catch (cause) {
      problem = errorText(toApiError(cause).code);
      tg.haptic('error');
      saving = false;
    }
  }
</script>

<form novalidate onsubmit={(event) => event.preventDefault()} onfocusout={onFieldLeft} onchange={onFieldLeft}>
  <fieldset disabled={saving}>
    <ScheduleFields bind:form {options} doseUnit={medication.doseUnit} errors={messages} />
  </fieldset>
</form>

<PreviewList preview={draftKey === null ? null : (previewed?.preview ?? null)} stale={draftKey !== null && !current && problem === null} {problem} />

{#if previewed && current}<p class="note">{previewed.preview.summary}</p>{/if}
<p class="note">{en.schedule.disclaimer}</p>

<style>
  .note {
    margin-block-end: var(--dg-space-4);
  }
</style>
