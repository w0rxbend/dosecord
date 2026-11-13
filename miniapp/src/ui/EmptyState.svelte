<script lang="ts">
  interface Props {
    message: string;
    actionLabel?: string;
    onAction?: () => void;
  }

  let { message, actionLabel, onAction }: Props = $props();
</script>

<!-- One sentence that says what will appear, and at most one action (MA-STATE-5). -->
<div class="section-body pad state">
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
</style>
