<script lang="ts">
  import { ApiError, newIdempotencyKey, toApiError } from '../../api/client';
  import type { ImportBatch, ImportSource, ImportTemplateKind, ImportView } from '../../api/types';
  import { useApp } from '../../app/context';
  import { dateLocale, t as en, errorText } from '../../i18n/index.svelte';
  import { csvBatches } from '../../import/csv-batches';
  import type { HealthType } from '../../import/health';
  import { HealthFileProblem, MAX_HEALTH_BYTES } from '../../import/health-file';
  import { deviceBatches, readHealth } from '../../import/read-health';
  import { Resource } from '../../state/resource.svelte';
  import Screen from '../../ui/Screen.svelte';
  import ImportHistory from './ImportHistory.svelte';
  import ImportPreview from './ImportPreview.svelte';
  import ImportSources from './ImportSources.svelte';

  /** A CSV file is read in memory; the largest one the app opens (PROPOSED, supervisor decision, 2026-11-14). */
  const MAX_CSV_BYTES = 20 * 1024 * 1024;
  /** MA-REP-6: until spike MA-SPK-6, device exports are offered on Telegram Desktop only. */
  const PHONES = new Set(['android', 'android_x', 'ios']);
  const HEALTH_TEXT = { too_large: 'healthTooLarge', unsupported: 'unsupported', not_health: 'notHealth' } as const;

  const { api, tg, toasts } = useApp();
  const items = new Resource<readonly ImportView[]>(() => api.imports());
  let step = $state<'choose' | 'reading' | 'sending' | 'preview'>('choose');
  let readBytes = $state(0);
  let preview = $state<ImportView | null>(null);
  let notice = $state<string | null>(null);
  let cancelReading = () => {};

  const megabytes = (bytes: number): string =>
    `${new Intl.NumberFormat(dateLocale(), { maximumFractionDigits: 1 }).format(bytes / 1_048_576)} MB`;

  function problemText(cause: unknown): string {
    if (cause instanceof HealthFileProblem) return en.imports[HEALTH_TEXT[cause.reason]];
    const error = toApiError(cause);
    const codes = error.fields.map((field) => field.code);
    if (error.code === 'consent_required') return en.imports.consentOff;
    if (error.code === 'quota_exceeded') return en.imports.tooMany;
    if (codes.includes('unreadable')) return en.imports.unreadable;
    if (codes.includes('unknown_columns')) return en.imports.unknownColumns;
    return error instanceof ApiError ? errorText(error.code) : en.imports.stopped;
  }

  async function downloadTemplate(kind: ImportTemplateKind): Promise<void> {
    const accepted = await tg.downloadFile({ url: api.importTemplateUrl(kind), fileName: `dosegram-template-${kind}.csv` });
    toasts.show(accepted ? en.reports.downloadStarted : en.reports.downloadUnavailable);
  }

  /** Reads the file, starts an import and sends its batches; the last answer is the preview. */
  async function run(source: ImportSource, read: () => Promise<readonly ImportBatch[]>): Promise<void> {
    notice = null;
    try {
      const batches = await read();
      step = 'sending';
      let view = await api.startImport(source, newIdempotencyKey());
      try {
        for (const batch of batches) view = await api.addImportBatch(view.id, batch, newIdempotencyKey());
      } catch (cause) {
        await api.removeImport(view.id).catch(() => undefined);
        throw cause;
      }
      preview = view;
      step = 'preview';
    } catch (cause) {
      step = 'choose';
      notice = problemText(cause);
      tg.haptic('error');
    }
  }

  async function pickCsv(file: File): Promise<void> {
    if (file.size > MAX_CSV_BYTES) notice = en.imports.csvTooLarge;
    else
      await run('dosegram_csv', async () => {
        const batches = csvBatches(await file.text());
        if (batches === null) throw new ApiError('validation', undefined, [{ field: 'csv', code: 'unreadable' }]);
        return batches;
      });
  }

  async function pickHealth(file: File, selected: readonly HealthType[]): Promise<void> {
    if (selected.length === 0) notice = en.imports.chooseType;
    else if (file.size > MAX_HEALTH_BYTES) notice = en.imports.healthTooLarge;
    else
      await run('apple_health', async () => {
        step = 'reading';
        readBytes = 0;
        const reading = readHealth(file, selected, (bytes) => (readBytes = bytes));
        cancelReading = () => {
          reading.cancel();
          step = 'choose';
        };
        return deviceBatches(await reading.result);
      });
  }

  async function cancelPreview(): Promise<void> {
    const open = preview;
    preview = null;
    step = 'choose';
    if (open) await api.removeImport(open.id).catch(() => undefined);
    await items.load();
  }

  async function commit(): Promise<void> {
    const open = preview;
    if (!open) return;
    try {
      const done = await api.commitImport(open.id, newIdempotencyKey());
      notice = en.imports.done(done.domains.reduce((n, d) => n + d.imported, 0));
      tg.haptic('success');
    } catch (cause) {
      notice = problemText(cause);
    }
    preview = null;
    step = 'choose';
    await items.load();
  }

  async function remove(view: ImportView): Promise<void> {
    const buttons = [
      { id: 'keep', text: en.deletion.keep },
      { id: 'remove', text: en.imports.remove, kind: 'destructive' as const },
    ];
    if ((await tg.popup({ message: en.imports.removeConfirm, buttons })) !== 'remove') return;
    try {
      await api.removeImport(view.id);
      toasts.show(en.imports.removed);
      await items.load();
    } catch (cause) {
      toasts.show(problemText(cause));
    }
  }
</script>

<!-- S-14 behaviour 6: the file is read on the device; the preview shows counts and duplicates; nothing is stored
     before the confirmation; an import is removed as a unit (PRODUCT_SPEC R-IMX-7 to R-IMX-9). -->
<Screen title={en.imports.title}>
  <p class="section pad">{en.imports.intro}</p>
  {#if notice}<p class="note section" role="status">{notice}</p>{/if}

  {#if step === 'reading' || step === 'sending'}
    <section class="section section-body pad stack" aria-live="polite">
      <p>{step === 'reading' ? en.imports.reading(megabytes(readBytes)) : en.imports.sending}</p>
      {#if step === 'reading'}
        <button class="btn btn-tinted" type="button" onclick={() => cancelReading()}>{en.imports.cancel}</button>
      {/if}
    </section>
  {:else if step === 'preview' && preview}
    <ImportPreview {preview} onCommit={commit} onCancel={cancelPreview} />
  {:else}
    <ImportSources phone={PHONES.has(tg.platform)} onTemplate={downloadTemplate} onCsv={pickCsv} onHealth={pickHealth} />
  {/if}

  <ImportHistory {items} onRemove={remove} />
</Screen>

<style>
  .stack {
    display: grid;
    gap: var(--dg-space-3);
    justify-items: start;
  }
</style>
