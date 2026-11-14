<script lang="ts">
  import type { PromptPreset, ReminderKind } from '../../api/types';
  import { t as en } from '../../i18n/index.svelte';
  import { dayAndClockIn } from './time';

  interface Props {
    kind: ReminderKind;
    presets: readonly PromptPreset[];
    timeZone: string;
    busy: boolean;
    onTurnOn: (presetId: string) => void;
    onTurnOff: (kind: string) => void;
  }

  let { kind, presets, timeZone, busy, onTurnOn, onTurnOff }: Props = $props();
  // Nothing is preselected: the preview shows after a choice, and only "Turn on" saves (ADR-023).
  let chosen = $state<string | undefined>(undefined);
  const preview = $derived(presets.find((preset) => preset.id === chosen));
</script>

<fieldset class="section">
  <legend class="section-title">
    {kind.name}: {kind.presetName ?? en.reminders.off}
    {#if kind.nextAt}<span class="muted"> · {en.reminders.next(dayAndClockIn(timeZone, kind.nextAt))}</span>{/if}
  </legend>
  <div class="section-body pad">
    <div class="chips">
      {#each presets as preset (preset.id)}
        <label class="chip">
          <input type="radio" name={`preset-${kind.kind}`} value={preset.id} bind:group={chosen} disabled={busy} />
          <span>{preset.name}</span>
        </label>
      {/each}
    </div>
    {#if preview}
      <p class="muted" role="status">
        {preview.preview.length > 0
          ? en.reminders.preview(preview.preview.map((at) => dayAndClockIn(timeZone, at)).join(', '))
          : en.reminders.noPreview}
      </p>
      <button class="btn btn-filled" type="button" disabled={busy} onclick={() => onTurnOn(preview.id)}>{en.reminders.turnOn}</button>
    {/if}
    {#if kind.presetId}
      <button class="btn btn-tinted" type="button" disabled={busy} onclick={() => onTurnOff(kind.kind)}>{en.reminders.turnOff}</button>
    {/if}
  </div>
</fieldset>

<style>
  .section-body {
    display: grid;
    gap: var(--dg-space-3);
  }
</style>
