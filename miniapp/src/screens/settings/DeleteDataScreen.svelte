<script lang="ts">
  import type { DeletionDomain } from '../../api/types';
  import { useApp } from '../../app/context';
  import { t as en } from '../../i18n/index.svelte';
  import Screen from '../../ui/Screen.svelte';

  const DOMAINS: readonly DeletionDomain[] = ['medication', 'hydration', 'mood', 'sleep', 'nutrition'];
  const { api, tg } = useApp();

  let domain = $state<DeletionDomain>('hydration');
  let status = $state('');
  let failure = $state('');
  let sending = $state(false);

  // A danger button, then a native confirmation, then the confirmation in the chat (MA-SET-7). The app deletes
  // nothing: the tap in the chat is the step-up (MINI_APP 8.8, MA-STEP-1).
  async function request(message: string, send: () => Promise<unknown>): Promise<void> {
    status = '';
    failure = '';
    const answer = await tg.popup({
      message,
      buttons: [
        { id: 'keep', text: en.deletion.keep },
        { id: 'delete', text: en.deletion.delete, kind: 'destructive' },
      ],
    });
    if (answer !== 'delete') return;
    sending = true;
    try {
      await send();
      status = en.deletion.sent;
      tg.haptic('warning');
    } catch {
      failure = en.deletion.notSent;
      tg.haptic('error');
    } finally {
      sending = false;
    }
  }

  const deleteDomain = (): Promise<void> =>
    request(en.deletion.domainConfirm(en.deletion.domains[domain]), () => api.requestDomainDeletion(domain));

  const deleteAccount = (): Promise<void> =>
    request(en.deletion.accountConfirm, () => api.requestAccountDeletion());
</script>

<Screen title={en.deletion.title}>
  {#if status}<p class="section sent" role="status">{status}</p>{/if}
  {#if failure}<p class="section error" role="alert">{failure}</p>{/if}

  <fieldset class="section">
    <legend class="section-title">{en.deletion.domainTitle}</legend>
    <div class="section-body pad">
      <p class="muted">{en.deletion.domainHelp}</p>
      <div class="choices">
        {#each DOMAINS as choice (choice)}
          <label class="choice">
            <input type="radio" name="deletion-domain" value={choice} checked={domain === choice} onchange={() => (domain = choice)} />
            <span>{en.deletion.domains[choice]}</span>
          </label>
        {/each}
      </div>
      <button type="button" class="btn btn-tinted danger" disabled={sending} onclick={deleteDomain}>{en.deletion.domainButton}</button>
    </div>
  </fieldset>

  <section class="section" aria-labelledby="delete-account">
    <h2 class="section-title" id="delete-account">{en.deletion.accountTitle}</h2>
    <div class="section-body pad">
      <ul class="facts">
        <li>{en.deletion.accountDeletes}</li>
        <li>{en.deletion.accountStops}</li>
        <li>{en.deletion.accountKeeps}</li>
        <li>{en.deletion.accountBackups}</li>
      </ul>
      <button type="button" class="btn btn-tinted danger" disabled={sending} onclick={deleteAccount}>{en.deletion.accountButton}</button>
    </div>
  </section>
</Screen>

<style>
  .choices {
    display: grid;
    gap: var(--dg-space-2);
    margin-block: var(--dg-space-3);
  }

  .choice {
    display: flex;
    align-items: center;
    gap: var(--dg-space-3);
    min-block-size: var(--dg-row-two-line);
  }

  .facts {
    display: grid;
    gap: var(--dg-space-2);
    margin-block-end: var(--dg-space-3);
    padding-inline-start: var(--dg-space-4);
    list-style: disc;
  }

  .sent {
    font-weight: 600;
  }

  .danger:not(:disabled) {
    color: var(--dg-danger);
  }

  .error {
    color: var(--dg-danger);
    font-weight: 600;
  }
</style>
