<script lang="ts">
  import { ApiError, newIdempotencyKey, toApiError } from '../../api/client';
  import type { ExportItem, ExportRequest } from '../../api/types';
  import { useApp } from '../../app/context';
  import { dateLocale, t as en, errorText } from '../../i18n/index.svelte';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import Screen from '../../ui/Screen.svelte';

  /** MA-REP-5: while a file waits or is made, the page asks again on this period. */
  const POLL_MS = 3000;
  const SUMMARY_DAYS: readonly number[] = [30, 90, 365];

  const { api, tg, toasts } = useApp();
  const items = new Resource<readonly ExportItem[]>(() => api.exports());
  let days = $state(90);
  let busy = $state(false);
  let notice = $state<string | null>(null);

  const pending = $derived(
    (items.data ?? []).some((item) => item.state === 'awaiting_confirmation' || item.state === 'preparing'),
  );

  // The confirmation happens in the chat: the page refreshes on return and while something is underway.
  $effect(() => tg.onActivated(() => void items.load()));
  $effect(() => {
    if (!pending) return;
    const timer = setInterval(() => void items.load(), POLL_MS);
    return () => clearInterval(timer);
  });

  const clock = (iso: string): string =>
    new Date(iso).toLocaleTimeString(dateLocale(), { hour: '2-digit', minute: '2-digit' });

  const dateText = (date: string): string =>
    new Date(`${date}T12:00:00Z`).toLocaleDateString(dateLocale(), { day: 'numeric', month: 'short', year: 'numeric' });

  function titleOf(item: ExportItem): string {
    if (item.kind === 'full_zip' || !item.from || !item.to) return en.reports.full;
    return en.reports.summaryOf(dateText(item.from), dateText(item.to));
  }

  function stateOf(item: ExportItem): string {
    switch (item.state) {
      case 'ready':
        return en.reports.ready(item.expiresAt ? clock(item.expiresAt) : '');
      case 'awaiting_confirmation':
        return en.reports.awaiting;
      case 'preparing':
        return en.reports.preparing;
      case 'downloaded':
        return en.reports.downloaded;
      case 'expired':
        return en.reports.expired;
      default:
        return en.reports.notMade;
    }
  }

  async function request(input: ExportRequest): Promise<void> {
    busy = true;
    notice = null;
    try {
      await api.requestExport(input, newIdempotencyKey());
      tg.haptic('success');
      notice = en.reports.confirmInChat;
      await items.load();
    } catch (cause) {
      const error = toApiError(cause);
      tg.haptic('error');
      notice =
        error.code === 'rate_limited' && error.retryAfterSeconds !== undefined
          ? en.reports.limit(clock(new Date(Date.now() + error.retryAfterSeconds * 1000).toISOString()))
          : errorText(error.code);
    } finally {
      busy = false;
    }
  }

  async function download(item: ExportItem): Promise<void> {
    try {
      const ticket = await api.downloadTicket(item.id);
      const accepted = await tg.downloadFile({ url: ticket.url, fileName: ticket.fileName });
      toasts.show(accepted ? en.reports.downloadStarted : en.reports.downloadUnavailable);
      await items.load();
    } catch (cause) {
      toasts.show(errorText(cause instanceof ApiError ? cause.code : 'server'));
    }
  }
</script>

<!-- S-14: the record summary and the full export. Nothing is made before the tap in the chat
     (MA-STEP-1); a file is fetched with a download ticket, never with the session (MA-REP-4). -->
<Screen title={en.reports.title}>
  <section class="section" aria-labelledby="summary-title">
    <h2 class="section-title" id="summary-title">{en.reports.summaryTitle}</h2>
    <div class="section-body pad stack">
      <p>{en.reports.summaryText}</p>
      <fieldset>
        <legend class="muted">{en.reports.period}</legend>
        <div class="chips">
          {#each SUMMARY_DAYS as option (option)}
            <label class="chip">
              <input type="radio" name="summary-days" value={option} bind:group={days} />
              <span>{en.reports.days(option)}</span>
            </label>
          {/each}
        </div>
      </fieldset>
      <button
        class="btn btn-tinted"
        type="button"
        disabled={busy}
        onclick={() => request({ kind: 'record_summary_pdf', days })}>{en.reports.makeSummary}</button
      >
    </div>
  </section>

  <section class="section" aria-labelledby="export-title">
    <h2 class="section-title" id="export-title">{en.reports.exportTitle}</h2>
    <div class="section-body pad stack">
      <p>{en.reports.exportText}</p>
      <button class="btn btn-tinted" type="button" disabled={busy} onclick={() => request({ kind: 'full_zip' })}
        >{en.reports.requestExport}</button
      >
    </div>
  </section>

  {#if notice}<p class="note section" role="status">{notice}</p>{/if}

  <section class="section" aria-labelledby="files-title">
    <h2 class="section-title" id="files-title">{en.reports.listTitle}</h2>
    <AsyncView resource={items} loadingLabel={en.reports.loading} skeletonRows={2}>
      {#snippet children(data)}
        {#if data.length === 0}
          <p class="section-body pad muted">{en.reports.none}</p>
        {:else}
          <ul class="section-body">
            {#each data as item (item.id)}
              <li class="row">
                <span class="text">
                  <span>{titleOf(item)}</span>
                  <span class="muted">{stateOf(item)}</span>
                </span>
                {#if item.state === 'ready'}
                  <button class="btn btn-filled" type="button" onclick={() => download(item)}>{en.reports.download}</button>
                {/if}
              </li>
            {/each}
          </ul>
        {/if}
      {/snippet}
    </AsyncView>
  </section>
</Screen>

<style>
  .stack {
    display: grid;
    gap: var(--dg-space-3);
    justify-items: start;
  }

  fieldset {
    border: 0;
    margin: 0;
    padding: 0;
    display: grid;
    gap: var(--dg-space-2);
  }

  .row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--dg-space-3);
    min-block-size: var(--dg-row-two-line);
    padding: var(--dg-space-3) var(--dg-space-4);
  }

  .text {
    display: grid;
    gap: var(--dg-space-1);
  }
</style>
