<script lang="ts">
  import type { Consents } from '../../api/types';
  import { useApp } from '../../app/context';
  import { t as en } from '../../i18n/index.svelte';

  let { consents }: { consents: Consents } = $props();
  const { tg } = useApp();
</script>

<!-- What is on, in the server's words, and where the rest is turned on: the chat. No checkbox and
     no switch, because the app records no consent of its own (MA-ROLE-2). -->
<p>{en.setup.consentText}</p>
<ul class="consents">
  {#each consents.items as item (item.purpose)}
    <li>
      <span>{item.title}</span>
      <span class="state" class:on={item.granted}>{item.granted ? en.setup.consentOn : en.privacy.off}</span>
      {#if !item.granted}<span class="muted why">{item.text}</span>{/if}
    </li>
  {/each}
</ul>
{#if consents.items.some((item) => !item.granted)}
  <button type="button" class="btn btn-tinted" onclick={() => tg.close()}>{en.setup.toChat}</button>
{/if}

<style>
  .consents {
    display: grid;
    inline-size: 100%;
    gap: var(--dg-space-1);
    margin: 0;
    padding: 0;
    list-style: none;
  }

  li {
    display: grid;
    grid-template-columns: 1fr auto;
    gap: 0 var(--dg-space-3);
    padding-block: var(--dg-space-1);
    border-block-end: 1px solid var(--dg-separator);
  }

  li:last-child {
    border-block-end: 0;
  }

  .state {
    color: var(--dg-text-muted);
  }

  .state.on {
    color: var(--dg-text);
    font-weight: 600;
  }

  .why {
    grid-column: 1 / -1;
  }
</style>
