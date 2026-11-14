<script lang="ts">
  import { toApiError } from '../../api/client';
  import type { DayProfile, DayProfileInput } from '../../api/types';
  import { useApp } from '../../app/context';
  import { t as en, errorText } from '../../i18n/index.svelte';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import Screen from '../../ui/Screen.svelte';
  import DayForm from '../reminders/DayForm.svelte';

  const { api, tg, router, toasts } = useApp();
  const day = new Resource<DayProfile>(async () => (await api.reminders()).day);

  let busy = $state(false);
  let failure = $state('');

  // The day saves on the version it was read at; work that ends before it starts is refused by
  // the form before any request (MA-SET-10).
  async function save(input: DayProfileInput): Promise<void> {
    const current = day.data;
    if (!current) return;
    busy = true;
    failure = '';
    try {
      day.set(await api.saveDayProfile(input, current.version));
      tg.haptic('success');
      toasts.show(en.reminders.daySaved);
      await router.back();
    } catch (cause) {
      failure = errorText(toApiError(cause).code);
      tg.haptic('error');
    } finally {
      busy = false;
    }
  }
</script>

<Screen title={en.dayProfile.title}>
  <p class="note section">{en.dayProfile.intro}</p>
  {#if failure}<p class="section error" role="alert">{failure}</p>{/if}
  <AsyncView resource={day} loadingLabel={en.dayProfile.loading} skeletonRows={5}>
    {#snippet children(data)}
      {#key data.version}
        <DayForm day={data} {busy} heading={false} onSave={(input) => void save(input)} />
      {/key}
    {/snippet}
  </AsyncView>
</Screen>

<style>
  .note {
    padding: 0;
  }

  .error {
    color: var(--dg-danger);
    font-weight: 600;
  }
</style>
