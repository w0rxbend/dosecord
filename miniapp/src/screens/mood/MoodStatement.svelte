<script lang="ts">
  import { en } from '../../i18n/en';

  let { statement }: { statement: string } = $props();
  let open = $state(false);
</script>

<!-- Shown on every mood screen for every value (MA-MOOD-7). Nothing here changes with a value. -->
<div class="section note-block">
  <p class="note">{statement}</p>
  <button type="button" class="btn" aria-expanded={open} aria-controls="mood-help" onclick={() => (open = !open)}>
    {en.mood.help}
  </button>
  {#if open}<p class="note" id="mood-help">{en.mood.helpText}</p>{/if}
</div>

<style>
  .note-block {
    display: grid;
    justify-items: start;
    gap: var(--dg-space-1);
  }
</style>
