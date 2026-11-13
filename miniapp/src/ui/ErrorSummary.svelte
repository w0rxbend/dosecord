<script lang="ts">
  interface Entry {
    /** The id of the control the error belongs to. */
    readonly controlId: string;
    readonly label: string;
    readonly message: string;
  }

  let { title, entries }: { title: string; entries: readonly Entry[] } = $props();

  // A button that moves focus, not a fragment link: the URL fragment belongs to the
  // Telegram script (MA-ROUTE-3).
  const focus = (controlId: string): void => document.getElementById(controlId)?.focus();
</script>

<!-- After a failed save: every error, each leading to its field (MINI_APP 7.4 behaviour 3). -->
<div class="section-body pad summary" role="alert">
  <h2>{title}</h2>
  <ul>
    {#each entries as entry (entry.controlId)}
      <li>
        <button type="button" class="btn" onclick={() => focus(entry.controlId)}>
          {entry.label}: {entry.message}
        </button>
      </li>
    {/each}
  </ul>
</div>

<style>
  .summary {
    margin-block-end: var(--dg-space-5);
    border: 2px solid var(--dg-danger);
  }

  h2 {
    font-size: var(--dg-text-body);
    font-weight: 600;
  }

  .btn {
    justify-content: flex-start;
    padding-inline: 0;
    text-align: start;
    font-weight: 400;
  }
</style>
