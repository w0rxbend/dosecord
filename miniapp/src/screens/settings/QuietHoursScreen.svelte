<script lang="ts">
  import { tick } from 'svelte';
  import { toApiError } from '../../api/client';
  import type { NotificationSettings } from '../../api/types';
  import { useApp } from '../../app/context';
  import { useMainButton } from '../../app/main-button.svelte';
  import { en, errorText } from '../../i18n/en';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import Field from '../../ui/Field.svelte';
  import Screen from '../../ui/Screen.svelte';

  const { api, tg, router, toasts } = useApp();
  let enabled = $state(false);
  let start = $state('22:30');
  let end = $state('07:00');
  let saving = $state(false);
  let failure = $state('');

  const loaded = new Resource<NotificationSettings>(async () => {
    const settings = await api.notificationSettings();
    enabled = settings.quietHours !== undefined;
    start = settings.quietHours?.start ?? start;
    end = settings.quietHours?.end ?? end;
    return settings;
  });

  // A range may cross midnight; equal start and end are refused (MA-SET-4).
  const empty = $derived(enabled && start === end);

  // Quiet hours have dependent fields, so they save with MainButton (MA-SET-1).
  useMainButton(tg, {
    text: en.quietHours.save,
    enabled: () => loaded.data !== undefined && !empty,
    busy: () => saving,
    onPress: () => void save(),
  });

  async function save(): Promise<void> {
    const settings = loaded.data;
    if (!settings || empty) return;
    saving = true;
    failure = '';
    try {
      await api.saveNotificationSettings({ discreet: settings.discreet, quietHours: enabled ? { start, end } : undefined }, settings.version);
      tg.haptic('success');
      toasts.show(en.quietHours.saved);
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

<Screen title={en.quietHours.title}>
  <AsyncView resource={loaded} loadingLabel={en.quietHours.loading} skeletonRows={3}>
    {#snippet children(settings)}
      {#if failure}<p class="section error" role="alert">{failure}</p>{/if}
      <div class="section section-body pad form">
        <label class="toggle">
          <input type="checkbox" role="switch" bind:checked={enabled} disabled={saving} />
          <span>{en.quietHours.enabled}</span>
        </label>
        {#if enabled}
          <div class="times">
            <Field id="quiet-start" label={en.quietHours.start} error={empty ? en.quietHours.empty : undefined}>
              {#snippet children(attributes)}
                <input {...attributes} class="input numeric" type="time" bind:value={start} disabled={saving} />
              {/snippet}
            </Field>
            <Field id="quiet-end" label={en.quietHours.end}>
              {#snippet children(attributes)}
                <input {...attributes} class="input numeric" type="time" bind:value={end} disabled={saving} />
              {/snippet}
            </Field>
          </div>
          <p class="muted">{en.quietHours.help}</p>
        {/if}
      </div>
      {#each settings.notes as note (note)}<p class="note">{note}</p>{/each}
    {/snippet}
  </AsyncView>
</Screen>

<style>
  .form {
    display: grid;
    gap: var(--dg-space-4);
  }

  .toggle {
    display: flex;
    align-items: center;
    gap: var(--dg-space-3);
    min-block-size: var(--dg-target);
    font-weight: 600;
  }

  .toggle input {
    inline-size: 24px;
    block-size: 24px;
  }

  .times {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: var(--dg-space-3);
  }

  .error {
    color: var(--dg-danger);
    font-weight: 600;
  }
</style>
