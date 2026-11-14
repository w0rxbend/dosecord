<script lang="ts">
  import { unlessOff } from '../../api/client';
  import { OFF, type Off, type WaterSettings } from '../../api/types';
  import { useApp } from '../../app/context';
  import { t as en } from '../../i18n/index.svelte';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import OffState from '../../ui/OffState.svelte';
  import Screen from '../../ui/Screen.svelte';
  import WaterSettingsForm from './WaterSettingsForm.svelte';

  const { api } = useApp();
  const settings = new Resource<WaterSettings | Off>(() => unlessOff(api.waterSettings()));
</script>

<Screen title={en.waterSettings.title}>
  <AsyncView resource={settings} loadingLabel={en.waterSettings.loading} skeletonRows={3}>
    {#snippet children(data)}
      {#if data === OFF}
        <div class="section"><OffState domain="hydration" /></div>
      {:else}
        <WaterSettingsForm settings={data} />
      {/if}
    {/snippet}
  </AsyncView>
</Screen>
