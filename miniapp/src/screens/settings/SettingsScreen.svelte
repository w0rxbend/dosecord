<script lang="ts">
  import type { AccountSettings, AccountSettingsInput, ImageTheme, NotificationSettings } from '../../api/types';
  import { useApp } from '../../app/context';
  import { setLocale, t as en } from '../../i18n/index.svelte';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import Screen from '../../ui/Screen.svelte';
  import LinkRow from '../../ui/LinkRow.svelte';

  interface Loaded {
    readonly account: AccountSettings;
    readonly notifications: NotificationSettings;
  }

  const THEMES: readonly ImageTheme[] = ['auto', 'light', 'dark'];
  const { api, tg } = useApp();
  const loaded = new Resource<Loaded>(async () => {
    const [account, notifications] = await Promise.all([api.settings(), api.notificationSettings()]);
    return { account, notifications };
  });
  $effect(() => tg.onActivated(() => void loaded.load()));

  let status = $state('');
  let failure = $state('');

  // A single choice saves on change, says "Saved" politely, and reverts with an inline error when
  // the request fails (MA-SET-1).
  async function saveAccount(change: Partial<AccountSettingsInput>): Promise<void> {
    const before = loaded.data;
    if (!before) return;
    const { language, timeZone, imageTheme, version } = before.account;
    const input = { language, timeZone, imageTheme, ...change };
    loaded.set({ ...before, account: { ...before.account, ...input } });
    // MA-SET-2: the new language shows at once; a failed save brings the previous one back.
    if (change.language !== undefined) setLocale(change.language);
    await commit(before, async () => ({ ...before, account: await api.saveSettings(input, version) }));
    setLocale(loaded.data?.account.language);
  }

  async function saveDiscreet(discreet: boolean): Promise<void> {
    const before = loaded.data;
    if (!before) return;
    const { quietHours, version } = before.notifications;
    loaded.set({ ...before, notifications: { ...before.notifications, discreet } });
    await commit(before, async () => ({
      ...before,
      notifications: await api.saveNotificationSettings({ discreet, quietHours }, version),
    }));
  }

  async function commit(before: Loaded, write: () => Promise<Loaded>): Promise<void> {
    tg.haptic('selection');
    status = '';
    failure = '';
    try {
      loaded.set(await write());
      status = en.settings.saved;
    } catch {
      loaded.set(before);
      failure = en.settings.notSaved;
      tg.haptic('error');
    }
  }
</script>

<Screen title={en.settings.title}>
  <p class="visually-hidden" role="status">{status}</p>
  {#if failure}<p class="section error" role="alert">{failure}</p>{/if}
  <AsyncView resource={loaded} loadingLabel={en.settings.loading} skeletonRows={5}>
    {#snippet children(data)}
      <section class="section" aria-labelledby="set-language">
        <h2 class="section-title" id="set-language">{en.settings.language}</h2>
        <div class="section-body pad">
          <label class="visually-hidden" for="set-language-choice">{en.settings.language}</label>
          <select id="set-language-choice" class="input" value={data.account.language} onchange={(event) => saveAccount({ language: event.currentTarget.value })}>
            {#each data.account.languages as choice (choice.value)}<option value={choice.value}>{choice.label}</option>{/each}
          </select>
        </div>
      </section>

      <div class="section section-body">
        <LinkRow path="/settings/tz" label={en.settings.timeZone} value={data.account.timeZone} />
      </div>

      <section class="section" aria-labelledby="set-notifications">
        <h2 class="section-title" id="set-notifications">{en.settings.notifications}</h2>
        <div class="section-body">
          <LinkRow
            path="/settings/notif"
            label={en.settings.quietHours}
            value={data.notifications.quietHours
              ? en.settings.quietRange(data.notifications.quietHours.start, data.notifications.quietHours.end)
              : en.settings.quietOff}
          />
          <label class="toggle">
            <span class="text"><span>{en.settings.discreet}</span><span class="muted">{en.settings.discreetHelp}</span></span>
            <input type="checkbox" role="switch" checked={data.notifications.discreet} onchange={(event) => saveDiscreet(event.currentTarget.checked)} />
          </label>
        </div>
        {#each data.notifications.notes as note (note)}<p class="note">{note}</p>{/each}
      </section>

      <div class="section section-body">
        <LinkRow path="/settings/privacy" label={en.settings.privacy} hint={en.settings.privacyHint} />
      </div>

      <div class="section section-body">
        <LinkRow path="/settings/reminders" label={en.settings.reminders} hint={en.settings.remindersHint} />
        <LinkRow path="/settings/day" label={en.settings.day} hint={en.settings.dayHint} />
      </div>

      <fieldset class="section">
        <legend class="section-title">{en.settings.imageTheme}</legend>
        <div class="section-body pad">
          <div class="segmented">
            {#each THEMES as theme (theme)}
              <label>
                <input type="radio" name="image-theme" value={theme} checked={data.account.imageTheme === theme} onchange={() => saveAccount({ imageTheme: theme })} />
                <span>{en.settings.themes[theme]}</span>
              </label>
            {/each}
          </div>
          <p class="muted help">{en.settings.imageThemeHelp}</p>
        </div>
      </fieldset>

      <section class="section" aria-labelledby="set-data">
        <h2 class="section-title" id="set-data">{en.settings.data}</h2>
        <div class="section-body">
          <LinkRow path="/insights/reports" label={en.settings.exportAll} hint={en.settings.exportHint} />
          <LinkRow path="/setup" label={en.settings.setup} hint={en.settings.setupHint} />
        </div>
      </section>

      <p class="note section">{en.settings.about}</p>
    {/snippet}
  </AsyncView>
</Screen>

<style>
  .toggle {
    display: flex;
    align-items: center;
    gap: var(--dg-space-3);
    min-block-size: var(--dg-row-two-line);
    padding: var(--dg-space-2) var(--dg-space-4);
  }


  .text {
    display: grid;
    flex: 1;
  }

  .help {
    margin-block-start: var(--dg-space-2);
  }

  .error {
    color: var(--dg-danger);
    font-weight: 600;
  }
</style>
