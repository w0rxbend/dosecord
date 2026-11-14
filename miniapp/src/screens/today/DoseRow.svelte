<script lang="ts">
  import type { Occurrence } from '../../api/types';
  import { doseStatusText } from '../../i18n/index.svelte';
  import StatusGlyph from '../../ui/StatusGlyph.svelte';

  let { occurrence }: { occurrence: Occurrence } = $props();
</script>

<!-- The glyph and the word carry the state; colour only supports them (MINI_APP 5.4). -->
<li class="row">
  <StatusGlyph state={occurrence.state} />
  <span class="numeric time">{occurrence.scheduledTime}</span>
  <span class="name">
    {occurrence.medicationName}
    <span class="muted numeric">{occurrence.doseText}</span>
  </span>
  <span class="muted numeric status">{doseStatusText(occurrence)}</span>
</li>

<style>
  .row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--dg-space-1) var(--dg-space-3);
    min-block-size: var(--dg-target);
    padding: var(--dg-space-2) var(--dg-space-4);
  }

  .name {
    flex: 1 1 8rem;
  }

  .name span {
    display: block;
  }

  .status {
    text-align: end;
  }
</style>
