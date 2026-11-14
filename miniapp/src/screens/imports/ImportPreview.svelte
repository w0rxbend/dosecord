<script lang="ts">
  import type { ImportView } from '../../api/types';
  import { dateLocale, t as en } from '../../i18n/index.svelte';

  interface Props {
    preview: ImportView;
    onCommit: () => void;
    onCancel: () => void;
  }

  let { preview, onCommit, onCancel }: Props = $props();

  const fresh = $derived(preview.domains.reduce((n, d) => n + d.fresh, 0));

  const dateText = (date: string): string =>
    new Date(`${date}T12:00:00Z`).toLocaleDateString(dateLocale(), { day: 'numeric', month: 'short', year: 'numeric' });
</script>

<!-- PRODUCT_SPEC R-IMX-8: counts per domain, the date range and the rows not imported, before anything is stored. -->
<section class="section" aria-labelledby="preview-title">
  <h2 class="section-title" id="preview-title">{en.imports.previewTitle}</h2>
  <div class="section-body pad stack">
    {#if preview.from && preview.to}<p>{en.imports.range(dateText(preview.from), dateText(preview.to))}</p>{/if}
    <ul class="plain">
      {#each preview.domains as domain (domain.domain)}
        <li>
          <strong>{en.imports.domains[domain.domain] ?? domain.domain}</strong>:
          {en.imports.counts(domain.fresh, domain.duplicates, domain.conflicts)}
        </li>
      {/each}
    </ul>
    {#if preview.invalid > 0}
      <p>{en.imports.invalid(preview.invalid)}</p>
      <ul class="plain muted">
        {#each preview.errors as error (`${error.line}:${error.field}`)}
          <li>{en.imports.errorLine(error.line, error.field, en.imports.problems[error.code] ?? error.code)}</li>
        {/each}
      </ul>
    {/if}
    {#if preview.skipped > 0}<p class="muted">{en.imports.skipped(preview.skipped)}</p>{/if}
    {#if preview.ignored > 0}<p class="muted">{en.imports.ignored(preview.ignored)}</p>{/if}
    {#if preview.withoutConsent > 0}<p class="muted">{en.imports.withoutConsent(preview.withoutConsent)}</p>{/if}
    {#if preview.noOffset > 0}<p class="muted">{en.imports.noOffset(preview.noOffset)}</p>{/if}
    {#if fresh === 0}<p>{en.imports.nothingNew}</p>{/if}
    <div class="buttons">
      <button class="btn btn-filled" type="button" disabled={fresh === 0} onclick={onCommit}>{en.imports.confirm}</button>
      <button class="btn btn-tinted" type="button" onclick={onCancel}>{en.imports.cancel}</button>
    </div>
  </div>
</section>

<style>
  .stack {
    display: grid;
    gap: var(--dg-space-3);
    justify-items: start;
  }

  .buttons {
    display: flex;
    flex-wrap: wrap;
    gap: var(--dg-space-2);
  }

  .plain {
    margin: 0;
    padding: 0;
    list-style: none;
    display: grid;
    gap: var(--dg-space-1);
  }
</style>
