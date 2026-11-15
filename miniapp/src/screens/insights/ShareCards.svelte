<script lang="ts">
  import { ApiError, newIdempotencyKey } from '../../api/client';
  import type { ReportCardOffer, ReportCards } from '../../api/types';
  import { useApp } from '../../app/context';
  import { errorText, t as en } from '../../i18n/index.svelte';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';

  // The shareable card of the chat's "Share a card" (VISUALS IMG-17): each button is the sentence its card states, so
  // the person sees what is sent before it is sent (VA-IMG-17b). The card goes to the chat through the report queue.
  const { api, tg, toasts } = useApp();
  const offers = new Resource<ReportCards>(() => api.reportCards());
  let sending = $state<string | null>(null);

  async function confirmed(read: ReportCards): Promise<boolean> {
    if (!read.discreet) return true;
    tg.haptic('warning');
    const answer = await tg.popup({
      message: en.insights.shareDiscreet,
      buttons: [
        { id: 'send', text: en.insights.shareSend },
        { id: 'cancel', text: en.insights.shareCancel },
      ],
    });
    return answer === 'send';
  }

  async function share(read: ReportCards, offer: ReportCardOffer): Promise<void> {
    if (!(await confirmed(read))) return;
    sending = offer.kind;
    try {
      await api.deliverReportCard(offer.kind, read.discreet, newIdempotencyKey());
      tg.haptic('success');
      toasts.show(en.insights.shareSent);
    } catch (cause) {
      tg.haptic('error');
      toasts.show(cause instanceof ApiError ? errorText(cause.code) : en.errors.notSaved);
    } finally {
      sending = null;
    }
  }
</script>

<section class="section" aria-labelledby="share-title">
  <h2 class="section-title" id="share-title">{en.insights.share}</h2>
  <AsyncView resource={offers} loadingLabel={en.insights.shareLoading} skeletonRows={1}>
    {#snippet children(read)}
      <div class="section-body pad card">
        {#if !read.available}
          <p class="muted">{en.insights.shareOff}</p>
        {:else if read.cards.length === 0}
          <p class="muted">{en.insights.shareNothing}</p>
        {:else}
          <p class="muted">{read.warning}</p>
          <div class="btn-row">
            {#each read.cards as offer (offer.kind)}
              <button
                type="button"
                class="btn btn-tinted"
                disabled={sending !== null}
                aria-busy={sending === offer.kind}
                onclick={() => void share(read, offer)}
              >
                {offer.sentence}
              </button>
            {/each}
          </div>
        {/if}
      </div>
    {/snippet}
  </AsyncView>
</section>

<style>
  .card {
    display: grid;
    gap: var(--dg-space-3);
  }

  /* A sentence is longer than a verb: the button wraps instead of overflowing at 320 px. */
  .btn {
    text-align: start;
    white-space: normal;
  }
</style>
