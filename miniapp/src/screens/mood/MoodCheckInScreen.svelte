<script lang="ts">
  import { unlessOff } from '../../api/client';
  import { OFF, type MoodRecorded, type Off } from '../../api/types';
  import { useApp } from '../../app/context';
  import MoodBlob from '../../charts/MoodBlob.svelte';
  import { en } from '../../i18n/en';
  import { moodAssets, type MoodAssets } from '../../state/mood-assets';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import OffState from '../../ui/OffState.svelte';
  import Screen from '../../ui/Screen.svelte';
  import CheckInForm from './CheckInForm.svelte';
  import MoodStatement from './MoodStatement.svelte';

  const { api, router } = useApp();
  const assets = new Resource<MoodAssets | Off>(() => unlessOff(moodAssets(api)));
  let saved = $state<MoodRecorded | null>(null);
  // A new form for "Add another": its MainButton and state start fresh.
  let round = $state(0);

  function another(): void {
    saved = null;
    round += 1;
  }
</script>

<Screen title={en.checkIn.title}>
  <AsyncView resource={assets} loadingLabel={en.checkIn.loading} skeletonRows={3}>
    {#snippet children(data)}
      {#if data === OFF}
        <div class="section"><OffState domain="mood" /></div>
      {:else}
        {#if saved}
          <!-- After saving: the blob with the saved labels, "Done" and "Add another". Nothing is sent
               to the chat on its own (MA-MOOD-9). -->
          <section class="section section-body pad saved" role="status">
            <MoodBlob asset={data.asset} stateId={saved.checkin.blobState} label={saved.checkin.label} size={140} breathe />
            <p class="label">{saved.checkin.label}</p>
            <p class="muted">{en.checkIn.saved}</p>
            <div class="btn-row">
              <button type="button" class="btn btn-filled" onclick={() => router.replace('/log/mood')}>{en.checkIn.done}</button>
              <button type="button" class="btn btn-tinted" onclick={another}>{en.checkIn.another}</button>
            </div>
          </section>
        {:else}
          {#key round}
            <CheckInForm asset={data.asset} scale={data.scale} onSaved={(recorded) => (saved = recorded)} />
          {/key}
        {/if}
        <MoodStatement statement={data.scale.statement} />
      {/if}
    {/snippet}
  </AsyncView>
</Screen>

<style>
  .saved {
    display: grid;
    justify-items: center;
    gap: var(--dg-space-2);
    text-align: center;
  }

  .label {
    font-size: var(--dg-text-title);
    font-weight: 600;
  }
</style>
