<script lang="ts">
  import type { TrackedDomain } from '../api/types';
  import Illustration from './Illustration.svelte';

  interface Props {
    message: string;
    actionLabel?: string;
    onAction?: () => void;
    /** The art above the sentence: one domain's cells, or a week of every domain. */
    art?: TrackedDomain | 'all';
    /** Cells already filled in the art, for a gate with progress. */
    filled?: number;
  }

  let { message, actionLabel, onAction, art, filled = 0 }: Props = $props();
</script>

<!-- One sentence that says what will appear, and at most one action (MA-STATE-5). -->
<div class="section-body pad state" class:with-art={art !== undefined}>
  {#if art}<Illustration kind={art} {filled} />{/if}
  <p>{message}</p>
  {#if actionLabel && onAction}
    <button type="button" class="btn btn-filled" onclick={onAction}>{actionLabel}</button>
  {/if}
</div>

<style>
  .state {
    display: grid;
    gap: var(--dg-space-3);
    justify-items: start;
  }

  .with-art {
    padding-block: var(--dg-space-6);
  }

  p {
    max-inline-size: 36ch;
  }
</style>
