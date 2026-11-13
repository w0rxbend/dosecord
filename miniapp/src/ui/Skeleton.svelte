<script lang="ts">
  let { label, rows = 3 }: { label: string; rows?: number } = $props();
</script>

<!-- A placeholder in the shape of the final layout; no spinner for content (MA-STATE-1). -->
<div class="section-body" role="status" aria-busy="true" aria-label={label}>
  {#each { length: rows } as _, index (index)}
    <div class="row">
      <span class="bar wide"></span>
      <span class="bar"></span>
    </div>
  {/each}
</div>

<style>
  .row {
    display: grid;
    gap: var(--dg-space-2);
    min-block-size: var(--dg-row-two-line);
    padding: var(--dg-space-3) var(--dg-space-4);
    align-content: center;
  }

  .bar {
    block-size: var(--dg-space-3);
    inline-size: 40%;
    border-radius: var(--dg-radius-full);
    background: var(--dg-neutral-weak);
  }

  .wide {
    inline-size: 70%;
  }
</style>
