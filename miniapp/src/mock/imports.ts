// The imports of the mock backend (ROADMAP G10.5, G10.7). It counts what a batch carries, as the
// server's preview does in shape: CSV rows by the template the header names, device records by
// type; a row the mock cannot read is refused with its line. It must not ship (MA-TEST-2).
import { ApiError } from '../api/client';
import type { ImportBatch, ImportDomainCounts, ImportSource, ImportView } from '../api/types';

const DOMAIN_OF_HEADER: readonly (readonly [string, string])[] = [
  ['onset_at', 'sleep'],
  ['meal_type', 'nutrition'],
  ['valence', 'mood'],
  ['amount_ml', 'hydration'],
];

export class MockImports {
  /** The imports, newest first. */
  items: ImportView[] = [];
  /** Whether the chat has recorded the import consent; tests turn it off. */
  consented = true;
  /** Every batch the server received, as sent: tests assert that no raw file is among them. */
  readonly batches: ImportBatch[] = [];
  private readonly seen = new Set<string>();
  private readonly staged = new Map<string, string[]>();
  private nextId = 1;

  constructor(private readonly now: () => Date) {}

  start(source: ImportSource): ImportView {
    if (!this.consented) throw new ApiError('consent_required', 'MOCK-0403');
    const view: ImportView = {
      id: `import-${this.nextId++}`,
      source,
      state: 'open',
      createdAt: this.now().toISOString(),
      domains: [],
      invalid: 0,
      skipped: 0,
      ignored: 0,
      withoutConsent: 0,
      noOffset: 0,
      errors: [],
    };
    this.items = [view, ...this.items];
    return view;
  }

  addBatch(id: string, batch: ImportBatch): ImportView {
    const view = this.open(id);
    this.batches.push(batch);
    const keys = this.staged.get(id) ?? [];
    const counts = new Map(view.domains.map((d) => [d.domain, { ...d }]));
    let invalid = view.invalid;
    const errors = [...view.errors];
    const add = (domain: string, key: string) => {
      const tally = counts.get(domain) ?? { domain, fresh: 0, duplicates: 0, conflicts: 0, imported: 0 };
      if (this.seen.has(key) || keys.includes(key)) tally.duplicates++;
      else {
        tally.fresh++;
        keys.push(key);
      }
      counts.set(domain, tally);
    };
    if (batch.csv !== undefined) {
      const [header = '', ...rows] = batch.csv.split(/\r?\n/).filter((line) => line.trim() !== '');
      const domain = DOMAIN_OF_HEADER.find(([column]) => header.split(',').includes(column))?.[1];
      if (domain === undefined) throw new ApiError('validation', 'MOCK-0422', [{ field: 'csv', code: 'unknown_columns' }]);
      rows.forEach((row, index) => {
        if (row.split(',').some((cell) => cell.trim() === '')) {
          invalid++;
          errors.push({ line: (batch.firstLine ?? 2) + index, field: 'row', code: 'missing_field' });
        } else add(domain, `${domain}|${row}`);
      });
    }
    for (const record of batch.records ?? []) {
      add(record.type === 'water' ? 'hydration' : 'sleep', JSON.stringify(record));
    }
    this.staged.set(id, keys);
    return this.replace(id, {
      ...view,
      domains: [...counts.values()],
      invalid,
      errors: errors.slice(0, 20),
      ignored: view.ignored + (batch.ignored ?? 0),
    });
  }

  commit(id: string): ImportView {
    const view = this.open(id);
    for (const key of this.staged.get(id) ?? []) this.seen.add(key);
    const domains: ImportDomainCounts[] = view.domains.map((d) => ({ ...d, imported: d.fresh }));
    return this.replace(id, { ...view, state: 'committed', committedAt: this.now().toISOString(), domains });
  }

  remove(id: string): ImportView {
    const view = this.find(id);
    if (view.state === 'open') return this.replace(id, { ...view, state: 'cancelled', closedAt: this.now().toISOString() });
    if (view.state !== 'committed') throw new ApiError('version_conflict', 'MOCK-0412');
    for (const key of this.staged.get(id) ?? []) this.seen.delete(key);
    return this.replace(id, { ...view, state: 'undone', closedAt: this.now().toISOString() });
  }

  private open(id: string): ImportView {
    const view = this.find(id);
    if (view.state !== 'open') throw new ApiError('version_conflict', 'MOCK-0412');
    return view;
  }

  private find(id: string): ImportView {
    const found = this.items.find((item) => item.id === id);
    if (!found) throw new ApiError('not_found', 'MOCK-0404');
    return found;
  }

  private replace(id: string, view: ImportView): ImportView {
    this.items = this.items.map((item) => (item.id === id ? view : item));
    return view;
  }
}
