<script lang="ts">
  import type { MedicationSummary } from '../../api/types';
  import PillTag from '../../ui/PillTag.svelte';

  interface Props {
    medication: MedicationSummary;
    /** Absent for an archived medication, which is read-only (MA-REG-4). */
    href?: string;
    onOpen: () => void;
  }

  let { medication, href, onOpen }: Props = $props();

  function open(event: MouseEvent): void {
    event.preventDefault();
    onOpen();
  }
</script>

{#snippet body()}
  <PillTag colour={medication.colourTag} shape={medication.shapeTag} />
  <span class="text">
    <span class="name">{medication.name}</span>
    <span class="muted numeric">{medication.detailText}</span>
    <!-- The schedule line is the server's text; the client composes none (MA-REG-2). -->
    <span class="muted numeric">
      {medication.scheduleSummary}{#if medication.nextText}. {medication.nextText}{/if}
    </span>
    <!-- Low stock is a plain statement in the text colour, not a warning (MA-REG-3). -->
    {#if medication.lowStockText}<span class="numeric">{medication.lowStockText}</span>{/if}
  </span>
{/snippet}

<li>
  {#if href}
    <a class="row" {href} onclick={open}>{@render body()}</a>
  {:else}
    <div class="row">{@render body()}</div>
  {/if}
</li>

<style>
  .row {
    display: flex;
    gap: var(--dg-space-3);
    align-items: flex-start;
    min-block-size: var(--dg-row-two-line);
    padding: var(--dg-space-3) var(--dg-space-4);
    color: inherit;
    text-decoration: none;
  }

  a.row:active {
    background: var(--dg-surface-alt);
  }

  .text {
    display: grid;
    gap: var(--dg-space-1);
  }

  .name {
    font-weight: 600;
  }
</style>
