<script lang="ts">
  import type { Consents } from '../../api/types';
  import { useApp } from '../../app/context';
  import { en } from '../../i18n/en';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import Screen from '../../ui/Screen.svelte';

  const { api, tg } = useApp();
  const consents = new Resource<Consents>(() => api.consents());
  // A consent given in the chat shows here when the person comes back (MINI_APP 8.7).
  $effect(() => tg.onActivated(() => void consents.load()));
</script>

<!-- Each purpose with its state and how to change it. The app shows no consent checkbox of its
     own: consent is given and withdrawn in the chat (MA-ROLE-2, MA-SET-6). -->
<Screen title={en.privacy.title}>
  <AsyncView resource={consents} loadingLabel={en.privacy.loading} skeletonRows={4}>
    {#snippet children(data)}
      <ul class="section section-body">
        {#each data.items as item (item.purpose)}
          <li class="row">
            <span class="head">
              <span class="title">{item.title}</span>
              <span class="state" class:on={item.granted}>{item.granted ? en.privacy.on : en.privacy.off}</span>
            </span>
            <span class="muted">{item.text}</span>
          </li>
        {/each}
      </ul>
      {#each data.notes as note (note)}<p class="note section">{note}</p>{/each}
    {/snippet}
  </AsyncView>
</Screen>

<style>
  .row {
    display: grid;
    gap: var(--dg-space-1);
    min-block-size: var(--dg-row-two-line);
    padding: var(--dg-space-3) var(--dg-space-4);
  }

  .head {
    display: flex;
    justify-content: space-between;
    gap: var(--dg-space-3);
  }

  .title {
    font-weight: 600;
  }

  .state {
    color: var(--dg-text-muted);
    font-weight: 600;
  }

  .state.on {
    color: var(--dg-recorded);
  }
</style>
