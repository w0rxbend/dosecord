<script lang="ts">
  import type { Occurrence, OccurrenceAction } from '../../api/types';
  import { doseStatusText, t as en } from '../../i18n/index.svelte';
  import { useApp } from '../../app/context';
  import StatusGlyph from '../../ui/StatusGlyph.svelte';

  interface Props {
    occurrence: Occurrence;
    onAction: (occurrence: Occurrence, action: OccurrenceAction) => void;
  }

  let { occurrence, onAction }: Props = $props();
  const { tg } = useApp();
  let choosingSnooze = $state(false);
  /** The server lists the lengths the reminder policy still allows; none, and Snooze is not offered (ADR-012). */
  const canSnooze = $derived(occurrence.snoozeChoicesMinutes.length > 0);

  function toggleSnooze(): void {
    tg.haptic('selection');
    choosingSnooze = !choosingSnooze;
  }
</script>

<!-- A due dose: each answer is one tap and one request (MA-TODAY-2). -->
<li class="pad card">
  <div class="head">
    <StatusGlyph state={occurrence.state} />
    <div>
      <p class="name">{occurrence.medicationName}</p>
      <p class="muted numeric">
        {occurrence.doseText}, {occurrence.scheduledTime}, {doseStatusText(occurrence)}
      </p>
    </div>
  </div>
  <div class="btn-row" role="group" aria-label={en.today.actionsFor(occurrence.medicationName)}>
    <button type="button" class="btn btn-filled" onclick={() => onAction(occurrence, { kind: 'taken' })}>
      {en.today.taken}
    </button>
    <button type="button" class="btn btn-tinted" onclick={() => onAction(occurrence, { kind: 'skipped' })}>
      {en.today.skip}
    </button>
    {#if canSnooze}
      <button type="button" class="btn btn-tinted" aria-expanded={choosingSnooze} onclick={toggleSnooze}>
        {en.today.snooze}
      </button>
    {/if}
  </div>
  {#if choosingSnooze && canSnooze}
    <div class="btn-row" role="group" aria-label={en.today.snoozeGroup}>
      {#each occurrence.snoozeChoicesMinutes as minutes (minutes)}
        <button
          type="button"
          class="btn btn-tinted numeric"
          onclick={() => onAction(occurrence, { kind: 'snoozed', minutes })}
        >
          {en.today.snoozeFor(minutes)}
        </button>
      {/each}
    </div>
  {/if}
</li>

<style>
  .card {
    display: grid;
    gap: var(--dg-space-3);
  }

  .head {
    display: flex;
    gap: var(--dg-space-3);
    align-items: flex-start;
  }

  .name {
    font-weight: 600;
  }
</style>
