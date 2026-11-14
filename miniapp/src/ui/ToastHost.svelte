<script lang="ts">
  import { t as en } from '../i18n/index.svelte';
  import type { Toasts } from '../state/toasts.svelte';

  let { toasts }: { toasts: Toasts } = $props();

  function runAction(): void {
    toasts.current?.action?.run();
    toasts.dismiss();
  }
</script>

<!-- The live region is always in the document so that a new message is announced once. -->
<div class="host" role="status" aria-live="polite">
  {#if toasts.current}
    <div class="toast">
      <span>{toasts.current.message}</span>
      {#if toasts.current.action}
        <button type="button" class="btn" onclick={runAction}>{toasts.current.action.label}</button>
      {:else}
        <button type="button" class="btn" onclick={() => toasts.dismiss()}>{en.common.dismiss}</button>
      {/if}
    </div>
  {/if}
</div>

<style>
  .host {
    position: fixed;
    inset-inline: 0;
    inset-block-end: calc(var(--dg-inset-bottom) + var(--dg-target) + var(--dg-space-6));
    display: flex;
    justify-content: center;
    padding-inline: var(--dg-space-4);
    pointer-events: none;
  }

  .toast {
    display: flex;
    align-items: center;
    gap: var(--dg-space-3);
    max-inline-size: var(--dg-content-max);
    padding-inline-start: var(--dg-space-4);
    border: 1px solid var(--dg-separator);
    border-radius: var(--dg-radius-section);
    background: var(--dg-surface);
    pointer-events: auto;
  }
</style>
