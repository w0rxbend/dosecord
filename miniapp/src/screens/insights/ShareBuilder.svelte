<script lang="ts">
  import { ApiError, newIdempotencyKey } from '../../api/client';
  import type { ReportCardPreview, ReportCards } from '../../api/types';
  import { useApp } from '../../app/context';
  import { errorText, t as en } from '../../i18n/index.svelte';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';

  // The share builder (MINI_APP 7.11, ROADMAP G9.5): choose a card and its period, see the card the chat would receive
  // with its text, then send it to the chat through the report queue. The preview is the PNG the chat would receive,
  // drawn by the server (ARCHITECTURE 9.5.1), so the app draws nothing of its own; without it the text is the card.
  let { read }: { read: ReportCards } = $props();

  const { api, tg, toasts } = useApp();
  // Nothing chosen yet means the first card and its first period.
  let card = $state<string | undefined>(undefined);
  let days = $state<number | undefined>(undefined);
  let sending = $state(false);

  const choice = $derived(read.builder.find((option) => option.card === card) ?? read.builder[0]);
  const period = $derived(choice?.periods.find((option) => option.days === days) ?? choice?.periods[0]);
  const kind = $derived(period?.kind ?? '');
  const preview = new Resource<ReportCardPreview>(() => api.previewReportCard(kind));

  // A preview follows every change of card or period; the first one was asked for when the builder opened.
  let shown: string | undefined;
  $effect(() => {
    const chosen = kind;
    if (shown === undefined) shown = chosen;
    if (chosen === shown || chosen === '') return;
    shown = chosen;
    void preview.load(() => api.previewReportCard(chosen));
  });

  function pickCard(chosen: string): void {
    tg.haptic('selection');
    card = chosen;
    days = undefined;
  }

  function pickPeriod(chosen: number): void {
    tg.haptic('selection');
    days = chosen;
  }

  const imageOf = (png: string): string => `data:image/png;base64,${png}`;

  async function confirmed(): Promise<boolean> {
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

  async function send(): Promise<void> {
    if (kind === '' || !(await confirmed())) return;
    sending = true;
    try {
      await api.deliverReportCard(kind, read.discreet, newIdempotencyKey());
      tg.haptic('success');
      toasts.show(en.insights.shareSent);
    } catch (cause) {
      tg.haptic('error');
      toasts.show(cause instanceof ApiError ? errorText(cause.code) : en.errors.notSaved);
    } finally {
      sending = false;
    }
  }
</script>

<section class="section" aria-labelledby="share-builder-title">
  <h2 class="section-title" id="share-builder-title">{read.builderTitle ?? en.insights.builder}</h2>
  <div class="section-body pad card">
    {#if read.builderWarning}<p class="muted">{read.builderWarning}</p>{/if}
    <fieldset>
      <legend>{en.insights.builderCard}</legend>
      <div class="segmented">
        {#each read.builder as option (option.card)}
          <label>
            <input
              type="radio"
              name="share-card"
              value={option.card}
              checked={option.card === choice?.card}
              onchange={() => pickCard(option.card)}
            />
            <span>{option.title}</span>
          </label>
        {/each}
      </div>
    </fieldset>
    {#if choice && choice.periods.length > 1}
      <fieldset>
        <legend>{en.insights.builderPeriod}</legend>
        <div class="segmented">
          {#each choice.periods as option (option.kind)}
            <label>
              <input
                type="radio"
                name="share-period"
                value={option.days}
                checked={option.kind === period?.kind}
                onchange={() => pickPeriod(option.days)}
              />
              <span class="numeric">{option.label}</span>
            </label>
          {/each}
        </div>
      </fieldset>
    {/if}
    <AsyncView resource={preview} loadingLabel={en.insights.builderLoading} skeletonRows={3}>
      {#snippet children(card)}
        <figure class="preview" aria-busy={preview.loading}>
          {#if card.png}<img src={imageOf(card.png)} alt={card.altText} />{/if}
          <figcaption>
            {#if card.png}
              <details>
                <summary>{en.insights.builderText}</summary>
                <p class="alt">{card.altText}</p>
              </details>
            {:else}
              <p class="alt">{card.altText}</p>
            {/if}
          </figcaption>
        </figure>
      {/snippet}
    </AsyncView>
    <button
      type="button"
      class="btn btn-filled"
      disabled={sending || preview.loading || preview.data === undefined}
      aria-busy={sending}
      onclick={() => void send()}
    >
      {en.insights.builderSend}
    </button>
  </div>
</section>

<style>
  .card {
    display: grid;
    gap: var(--dg-space-3);
  }

  fieldset {
    border: 0;
    margin: 0;
    padding: 0;
    display: grid;
    gap: var(--dg-space-2);
  }

  .preview {
    margin: 0;
    display: grid;
    gap: var(--dg-space-2);
  }

  /* The card keeps its own proportions at every width; it never scrolls sideways. */
  .preview img {
    inline-size: 100%;
    max-inline-size: 420px;
    block-size: auto;
    border-radius: var(--dg-radius-control);
  }

  /* The text equivalent is the card's own words, line breaks kept. */
  .alt {
    white-space: pre-line;
  }
</style>
