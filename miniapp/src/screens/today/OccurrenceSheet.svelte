<script lang="ts">
  import type { Occurrence, OccurrenceAction } from '../../api/types';
  import { doseStatusText, t as en } from '../../i18n/index.svelte';

  interface Props {
    occurrence: Occurrence;
    /** Absent on a read-only day: the sheet shows the record and no corrections. */
    onAction?: (occurrence: Occurrence, action: OccurrenceAction) => void;
    onClose: () => void;
  }

  let { occurrence, onAction, onClose }: Props = $props();

  const openForAnswer = $derived(occurrence.state === 'due' || occurrence.state === 'pending' || occurrence.state === 'snoozed');
  const record = $derived(recordedAction(occurrence));
  const showSafety = $derived(onAction !== undefined && (occurrence.state === 'missed' || occurrence.state === 'unknown'));

  function recordedAction(current: Occurrence): { time: string; action: string } | undefined {
    const copy = en.todaySheet;
    switch (current.state) {
      case 'taken':
        return current.resolvedTime === undefined ? undefined : { time: current.resolvedTime, action: copy.taken };
      case 'taken_late':
        return current.resolvedTime === undefined ? undefined : { time: current.resolvedTime, action: copy.takenLate };
      case 'snoozed':
        return current.snoozedUntil === undefined ? undefined : { time: current.snoozedUntil, action: copy.snoozed };
      case 'skipped':
        return { time: current.scheduledTime, action: copy.skipped };
      case 'missed':
        return { time: current.scheduledTime, action: copy.missed };
      case 'unknown':
        return { time: current.scheduledTime, action: copy.unknown };
      default:
        return undefined;
    }
  }

  function correct(action: OccurrenceAction): void {
    onAction?.(occurrence, action);
  }
</script>

<div class="section" role="dialog" aria-labelledby="today-sheet-title">
  <div class="section-body pad sheet">
    <h2 id="today-sheet-title">{occurrence.medicationName}</h2>
    <p><span class="muted">{en.todaySheet.scheduled}</span> <span class="numeric">{occurrence.scheduledTime}</span></p>
    <p><span class="muted">{en.todaySheet.state}</span> <span>{doseStatusText(occurrence)}</span></p>
    {#if record}
      <ul aria-label={en.todaySheet.history}>
        <li><span class="numeric">{record.time}</span> <span>{record.action}</span></li>
      </ul>
    {/if}
    {#if onAction}
      <div class="btn-row">
        <button type="button" class="btn btn-filled" onclick={() => correct({ kind: 'taken' })}>{en.todaySheet.changeTaken}</button>
        <button type="button" class="btn btn-tinted" onclick={() => correct({ kind: 'skipped' })}>{en.todaySheet.changeSkipped}</button>
        {#if !openForAnswer}
          <button type="button" class="btn btn-tinted" onclick={() => correct({ kind: 'undo' })}>{en.todaySheet.undo}</button>
        {/if}
      </div>
      {#if openForAnswer && occurrence.snoozeChoicesMinutes.length > 0}
        <div class="btn-row" role="group" aria-label={en.todaySheet.snooze}>
          {#each occurrence.snoozeChoicesMinutes as minutes (minutes)}
            <button type="button" class="btn btn-tinted numeric" onclick={() => correct({ kind: 'snoozed', minutes })}>
              {en.todaySheet.snoozeFor(minutes)}
            </button>
          {/each}
        </div>
      {/if}
      <!-- MA-TODAY-6: the standing sentence, and nothing else about what to do. -->
      {#if showSafety}<p>{en.todaySheet.safety}</p>{/if}
    {/if}
    <button type="button" class="btn" onclick={onClose}>{en.todaySheet.close}</button>
  </div>
</div>

<style>
  .sheet {
    display: grid;
    gap: var(--dg-space-3);
  }

  h2 {
    font-size: var(--dg-text-title);
  }
</style>
