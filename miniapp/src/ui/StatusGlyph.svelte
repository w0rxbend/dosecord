<script lang="ts">
  import type { DoseState } from '../api/types';

  let { state }: { state: DoseState } = $props();

  const recorded = $derived(state === 'taken' || state === 'taken_late');
</script>

<!-- One glyph per dose state (MINI_APP 5.4). The shape carries the meaning and the word sits
     next to it, so the glyph itself is hidden from assistive technology. -->
<svg class="glyph {state}" class:recorded viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">
  {#if recorded}
    <circle cx="12" cy="12" r="9" class="fill" />
    <path d="M7.5 12.5l3 3 6-6.5" class="mark" />
  {:else if state === 'due'}
    <circle cx="12" cy="12" r="9" class="line" />
    <circle cx="12" cy="12" r="4" class="fill" />
  {:else if state === 'snoozed'}
    <circle cx="12" cy="12" r="9" class="line" />
    <path d="M12 7v5l3 2" class="line" />
  {:else if state === 'skipped'}
    <circle cx="12" cy="12" r="9" class="line" />
    <path d="M7.5 12h9" class="line" />
  {:else if state === 'missed'}
    <circle cx="12" cy="12" r="9" class="line" />
    <path d="M6 18L18 6" class="line" />
  {:else if state === 'unknown'}
    <circle cx="12" cy="12" r="9" class="line dashed" />
    <path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.7.4-1 .9-1 1.7M12 16.5v.5" class="line" />
  {:else}
    <circle cx="12" cy="12" r="9" class="line" />
  {/if}
</svg>

<style>
  .glyph {
    flex: none;
    color: var(--dg-text-muted);
  }

  .due {
    color: var(--dg-accent-text);
  }

  .recorded {
    color: var(--dg-recorded);
  }

  /* Skipped and missed are neutral, never red (MA-DS-3). */
  .skipped,
  .missed {
    color: var(--dg-neutral-strong);
  }

  .fill {
    fill: currentColor;
  }

  .line {
    fill: none;
    stroke: currentColor;
    stroke-width: 2;
    stroke-linecap: round;
  }

  .dashed {
    stroke-dasharray: 3 3;
  }

  .mark {
    fill: none;
    stroke: var(--dg-surface);
    stroke-width: 2;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
</style>
