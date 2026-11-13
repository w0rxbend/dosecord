<script lang="ts">
  import { useApp } from '../app/context';

  interface Props {
    path: string;
    label: string;
    /** A short current value at the end of the row, such as "Off" or a zone. */
    value?: string;
    /** A sentence under the label. */
    hint?: string;
  }

  let { path, label, value, hint }: Props = $props();
  const { router } = useApp();
</script>

<!-- A row that navigates is one link; its accessible name is the whole row text (MINI_APP 5.6). -->
<a
  class="row"
  href={router.hrefFor(path)}
  onclick={(event) => {
    event.preventDefault();
    void router.navigate(path);
  }}
>
  <span class="text">
    <span>{label}</span>
    {#if hint}<span class="muted">{hint}</span>{/if}
  </span>
  {#if value}<span class="value muted">{value}</span>{/if}
  <span class="chevron" aria-hidden="true">›</span>
</a>

<style>
  .row {
    display: flex;
    align-items: center;
    gap: var(--dg-space-3);
    min-block-size: var(--dg-target);
    padding: var(--dg-space-2) var(--dg-space-4);
    color: inherit;
    text-decoration: none;
  }

  .text {
    display: grid;
    flex: 1 1 auto;
    overflow-wrap: normal;
  }

  .value {
    flex: 0 1 auto;
    text-align: end;
  }

  .chevron {
    color: var(--dg-text-muted);
    font-size: var(--dg-text-title);
  }
</style>
