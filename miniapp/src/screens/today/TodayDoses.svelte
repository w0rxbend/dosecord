<script lang="ts">
  import type { Occurrence, OccurrenceAction } from '../../api/types';
  import { en } from '../../i18n/en';
  import DoseCard from './DoseCard.svelte';
  import DoseRow from './DoseRow.svelte';
  import { EARLIER_COLLAPSE_ABOVE, groupOccurrences } from './group';

  interface Props {
    occurrences: readonly Occurrence[];
    readOnly: boolean;
    /** Read-only because the day has not come; false when doses are answered elsewhere. */
    planned: boolean;
    onAction: (occurrence: Occurrence, action: OccurrenceAction) => void;
  }

  let { occurrences, readOnly, planned, onAction }: Props = $props();
  let earlierOpen = $state(false);

  const groups = $derived(groupOccurrences(occurrences));
  const collapsible = $derived(groups.earlier.length > EARLIER_COLLAPSE_ABOVE);
</script>

{#if groups.dueNow.length > 0}
  <section class="section" aria-labelledby="due-title">
    <h2 class="section-title" id="due-title">{en.today.dueNow}</h2>
    <ul class="section-body">
      {#each groups.dueNow as occurrence (occurrence.id)}
        {#if readOnly}
          <DoseRow {occurrence} />
        {:else}
          <DoseCard {occurrence} {onAction} />
        {/if}
      {/each}
    </ul>
  </section>
{/if}

{#if groups.later.length > 0}
  <section class="section" aria-labelledby="later-title">
    <h2 class="section-title" id="later-title">{en.today.later}</h2>
    <ul class="section-body">
      {#each groups.later as occurrence (occurrence.id)}
        <DoseRow {occurrence} />
      {/each}
    </ul>
    {#if readOnly && planned}<p class="note">{en.today.planned}</p>{/if}
  </section>
{/if}

{#if groups.earlier.length > 0}
  <section class="section" aria-labelledby="earlier-title">
    <h2 class="section-title" id="earlier-title">{en.today.earlier(groups.earlier.length)}</h2>
    {#if collapsible}
      <button
        type="button"
        class="btn"
        aria-expanded={earlierOpen}
        aria-controls="earlier-list"
        onclick={() => (earlierOpen = !earlierOpen)}
      >
        {earlierOpen ? en.today.hideEarlier : en.today.showEarlier}
      </button>
    {/if}
    {#if !collapsible || earlierOpen}
      <ul class="section-body" id="earlier-list">
        {#each groups.earlier as occurrence (occurrence.id)}
          <DoseRow {occurrence} />
        {/each}
      </ul>
    {/if}
  </section>
{/if}
