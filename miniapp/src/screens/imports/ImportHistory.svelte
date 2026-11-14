<script lang="ts">
  import type { ImportView } from '../../api/types';
  import { dateLocale, t as en } from '../../i18n/index.svelte';
  import type { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';

  interface Props {
    items: Resource<readonly ImportView[]>;
    onRemove: (view: ImportView) => void;
  }

  let { items, onRemove }: Props = $props();

  const dateText = (iso: string): string =>
    new Date(iso).toLocaleDateString(dateLocale(), { day: 'numeric', month: 'short', year: 'numeric' });

  function stateOf(view: ImportView): string {
    switch (view.state) {
      case 'committed':
        return en.imports.imported(view.domains.reduce((n, d) => n + d.imported, 0));
      case 'cancelled':
        return en.imports.cancelled;
      case 'undone':
        return en.imports.undone;
      default:
        return en.imports.open;
    }
  }
</script>

<!-- R-IMX-9: a committed import can be removed as a unit at any time. -->
<section class="section" aria-labelledby="imports-title">
  <h2 class="section-title" id="imports-title">{en.imports.listTitle}</h2>
  <AsyncView resource={items} loadingLabel={en.imports.loading} skeletonRows={2}>
    {#snippet children(data)}
      {#if data.length === 0}
        <p class="section-body pad muted">{en.imports.none}</p>
      {:else}
        <ul class="section-body">
          {#each data as item (item.id)}
            <li class="row">
              <span class="text">
                <span>{en.imports.source[item.source] ?? item.source}, {dateText(item.createdAt)}</span>
                <span class="muted">{stateOf(item)}</span>
              </span>
              {#if item.state === 'committed'}
                <button class="btn btn-tinted" type="button" onclick={() => onRemove(item)}>{en.imports.remove}</button>
              {/if}
            </li>
          {/each}
        </ul>
      {/if}
    {/snippet}
  </AsyncView>
</section>

<style>
  .row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--dg-space-3);
    min-block-size: var(--dg-row-two-line);
    padding: var(--dg-space-3) var(--dg-space-4);
  }

  .text {
    display: grid;
    gap: var(--dg-space-1);
  }
</style>
