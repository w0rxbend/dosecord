<script lang="ts">
  import { tick } from 'svelte';
  import { toApiError } from '../../api/client';
  import type { AccountSettings, TimeZoneChoice, TimeZonePreview } from '../../api/types';
  import { useApp } from '../../app/context';
  import { useMainButton } from '../../app/main-button.svelte';
  import { t as en, errorText } from '../../i18n/index.svelte';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import Screen from '../../ui/Screen.svelte';

  interface Loaded {
    readonly account: AccountSettings;
    readonly zones: readonly TimeZoneChoice[];
  }

  /** At most this many rows are in the document; typing narrows the list (MA-PERF-2). */
  const SHOWN_ROWS = 40;
  const { api, tg, router, toasts } = useApp();
  const deviceZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const loaded = new Resource<Loaded>(async () => {
    const [account, zones] = await Promise.all([api.settings(), api.timeZones()]);
    return { account, zones };
  });

  let query = $state('');
  let chosen = $state<string | null>(null);
  let preview = $state<TimeZonePreview | null>(null);
  let saving = $state(false);
  let failure = $state('');

  const matches = $derived(
    (loaded.data?.zones ?? []).filter((zone) => zone.id.toLowerCase().replaceAll('_', ' ').includes(query.trim().toLowerCase().replaceAll('_', ' '))),
  );

  // Never saves a zone without showing its preview first (MA-SET-3).
  async function choose(zone: string): Promise<void> {
    tg.haptic('selection');
    chosen = zone;
    preview = null;
    failure = '';
    try {
      preview = await api.previewTimeZone(zone);
    } catch (cause) {
      failure = errorText(toApiError(cause).code);
    }
  }

  useMainButton(tg, {
    text: en.timeZone.save,
    enabled: () => preview !== null && preview.timeZone !== loaded.data?.account.timeZone,
    busy: () => saving,
    onPress: () => void save(),
  });

  async function save(): Promise<void> {
    const account = loaded.data?.account;
    if (!account || !preview) return;
    saving = true;
    try {
      const { language, imageTheme, version } = account;
      await api.saveSettings({ language, imageTheme, timeZone: preview.timeZone }, version);
      tg.haptic('success');
      toasts.show(en.timeZone.saved);
      await tick();
      await router.back();
    } catch (cause) {
      failure = errorText(toApiError(cause).code);
      tg.haptic('error');
    } finally {
      saving = false;
    }
  }
</script>

<Screen title={en.timeZone.title}>
  <AsyncView resource={loaded} loadingLabel={en.timeZone.loading} skeletonRows={5}>
    {#snippet children(data)}
      <div class="section section-body pad current">
        <p>{en.timeZone.current(data.account.timeZone)}</p>
        {#if deviceZone && deviceZone !== data.account.timeZone}
          <p class="muted">{en.timeZone.device(deviceZone)}</p>
          <button type="button" class="btn btn-tinted" onclick={() => choose(deviceZone)}>{en.timeZone.useDevice}</button>
        {/if}
      </div>

      {#if failure}<p class="section error" role="alert">{failure}</p>{/if}
      {#if preview}
        <section class="section" aria-labelledby="tz-preview" aria-live="polite">
          <h2 class="section-title" id="tz-preview">{en.timeZone.preview}: {preview.timeZone}</h2>
          <div class="section-body pad lines">
            <p class="numeric">{preview.localTime}</p>
            {#each preview.lines as line (line)}<p>{line}</p>{/each}
          </div>
        </section>
      {/if}

      <div class="section">
        <label class="visually-hidden" for="tz-search">{en.timeZone.search}</label>
        <input id="tz-search" class="input" type="search" placeholder={en.timeZone.search} autocomplete="off" bind:value={query} />
      </div>
      <fieldset class="section section-body">
        <legend class="visually-hidden">{en.timeZone.title}</legend>
        {#each matches.slice(0, SHOWN_ROWS) as zone (zone.id)}
          <label class="row">
            <input type="radio" name="time-zone" value={zone.id} checked={chosen === zone.id} onchange={() => choose(zone.id)} />
            <span class="name">{zone.id.replaceAll('_', ' ')}</span>
            <span class="muted numeric">{zone.offset}</span>
          </label>
        {:else}
          <p class="pad">{en.timeZone.noMatch}</p>
        {/each}
      </fieldset>
    {/snippet}
  </AsyncView>
</Screen>

<style>
  .current,
  .lines {
    display: grid;
    gap: var(--dg-space-2);
    justify-items: start;
  }

  .row {
    display: flex;
    align-items: center;
    gap: var(--dg-space-3);
    min-block-size: var(--dg-target);
    padding: 0 var(--dg-space-4);
  }

  .row input {
    inline-size: 20px;
    block-size: 20px;
  }

  .name {
    flex: 1;
  }

  .error {
    color: var(--dg-danger);
    font-weight: 600;
  }
</style>
