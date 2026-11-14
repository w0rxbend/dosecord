<script lang="ts">
  import type { ApiError } from '../api/client';
  import { t as en, errorText } from '../i18n/index.svelte';

  let { error, onRetry }: { error: ApiError; onRetry: () => void } = $props();
</script>

<!-- Says what happened and what to do; shown in the page, never only as a toast (MA-STATE-6, 8). -->
<div class="section-body pad state" role="alert">
  <p>{errorText(error.code)}</p>
  {#if error.reference}<p class="muted numeric">{en.common.reference(error.reference)}</p>{/if}
  <button type="button" class="btn btn-tinted" onclick={onRetry}>{en.common.tryAgain}</button>
</div>

<style>
  .state {
    display: grid;
    gap: var(--dg-space-3);
    justify-items: start;
  }
</style>
