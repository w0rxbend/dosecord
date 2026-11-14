// Apple Health export.xml reduced on the device to normalised records (PRODUCT_SPEC R-IMX-7,
// ROADMAP G10.7, MINI_APP S-14): the file is read as a stream, one `<Record …>` tag at a time, and
// only the types the user selected become records. Nothing else of the file is kept: the scanner
// holds at most the unfinished tag of the current chunk.

export type HealthType = 'water' | 'sleep';

/** A record as the server takes it (ImportBatchInput.records). */
export interface DeviceRecord {
  readonly type: 'water' | 'sleep_main' | 'sleep_nap';
  readonly at?: string;
  readonly amountMl?: number;
  readonly onset?: string;
  readonly wake?: string;
}

export interface HealthResult {
  readonly records: DeviceRecord[];
  /** Records of the file that are not imported: other types, unselected types, in-bed and awake samples. */
  readonly ignored: number;
  /** Body mass samples: counted apart, because body weight waits for the measurements diary (ROADMAP G12.1). */
  readonly bodyMass: number;
  readonly first?: string;
  readonly last?: string;
}

const WATER = 'HKQuantityTypeIdentifierDietaryWater';
const SLEEP = 'HKCategoryTypeIdentifierSleepAnalysis';
const BODY_MASS = 'HKQuantityTypeIdentifierBodyMass';
const ASLEEP = new Set([
  'HKCategoryValueSleepAnalysisAsleep',
  'HKCategoryValueSleepAnalysisAsleepUnspecified',
  'HKCategoryValueSleepAnalysisAsleepCore',
  'HKCategoryValueSleepAnalysisAsleepDeep',
  'HKCategoryValueSleepAnalysisAsleepREM',
]);
const MILLILITRES: Readonly<Record<string, number>> = { mL: 1, L: 1000, fl_oz_us: 29.5735, cup_us: 236.588 };
const OPEN = '<Record';

/** `2024-01-02 07:30:00 +0200` → ISO 8601 with the offset, and the local date the device wrote. */
export function healthTime(text: string): { iso: string; day: string } | null {
  const match = /^(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2}) ([+-])(\d{2})(\d{2})$/.exec(text.trim());
  if (!match) return null;
  return { iso: `${match[1]}T${match[2]}${match[3]}${match[4]}:${match[5]}`, day: match[1]! };
}

/** The attributes of one tag's text: `name="value"` pairs. */
function attributes(tag: string): Map<string, string> {
  const found = new Map<string, string>();
  const pattern = /([A-Za-z_:][\w:.-]*)\s*=\s*"([^"]*)"/g;
  for (let match = pattern.exec(tag); match !== null; match = pattern.exec(tag)) found.set(match[1]!, match[2]!);
  return found;
}

/** A sleep sample as the night joiner reads it. */
export interface SleepSample {
  readonly start: number;
  readonly end: number;
  /** The local date of the end, as the device wrote it. */
  readonly day: string;
}

/**
 * The streaming scanner. `push` takes the text in any chunking; `maxBuffered` is the longest text it
 * ever held, which the memory test reads.
 */
export class HealthScanner {
  readonly water: DeviceRecord[] = [];
  readonly sleep: SleepSample[] = [];
  ignored = 0;
  bodyMass = 0;
  maxBuffered = 0;
  first: string | undefined;
  last: string | undefined;
  private buffer = '';

  constructor(private readonly selected: ReadonlySet<HealthType>) {}

  push(chunk: string): void {
    this.buffer += chunk;
    this.maxBuffered = Math.max(this.maxBuffered, this.buffer.length);
    let from = 0;
    for (;;) {
      const open = this.buffer.indexOf(OPEN, from);
      if (open < 0) {
        // Keep only what could be the start of a tag split by the chunk boundary.
        this.buffer = this.buffer.slice(Math.max(from, this.buffer.length - OPEN.length));
        return;
      }
      const next = this.buffer.charAt(open + OPEN.length);
      if (next === '') {
        this.buffer = this.buffer.slice(open);
        return;
      }
      if (!/\s/.test(next)) {
        from = open + OPEN.length;
        continue;
      }
      const close = this.tagEnd(open);
      if (close < 0) {
        this.buffer = this.buffer.slice(open);
        return;
      }
      this.record(attributes(this.buffer.slice(open, close)));
      from = close + 1;
    }
  }

  /** The `>` that ends the tag starting at `open`, outside quoted values; -1 when it is not in the buffer yet. */
  private tagEnd(open: number): number {
    let quoted = false;
    for (let i = open; i < this.buffer.length; i++) {
      const c = this.buffer.charCodeAt(i);
      if (c === 34) quoted = !quoted;
      else if (c === 62 && !quoted) return i;
    }
    return -1;
  }

  private record(tag: Map<string, string>): void {
    const type = tag.get('type');
    if (type === WATER && this.selected.has('water')) this.waterOf(tag);
    else if (type === SLEEP && this.selected.has('sleep') && ASLEEP.has(tag.get('value') ?? '')) this.sleepOf(tag);
    else {
      this.ignored++;
      if (type === BODY_MASS) this.bodyMass++;
    }
  }

  private waterOf(tag: Map<string, string>): void {
    const at = healthTime(tag.get('startDate') ?? '');
    const factor = MILLILITRES[tag.get('unit') ?? ''];
    const value = Number(tag.get('value'));
    if (at === null || factor === undefined || !Number.isFinite(value)) {
      this.ignored++;
      return;
    }
    this.seen(at.day);
    this.water.push({ type: 'water', at: at.iso, amountMl: Math.round(value * factor) });
  }

  private sleepOf(tag: Map<string, string>): void {
    const start = healthTime(tag.get('startDate') ?? '');
    const end = healthTime(tag.get('endDate') ?? '');
    if (start === null || end === null) {
      this.ignored++;
      return;
    }
    this.seen(end.day);
    this.sleep.push({ start: Date.parse(start.iso), end: Date.parse(end.iso), day: end.day });
  }

  private seen(day: string): void {
    if (this.first === undefined || day < this.first) this.first = day;
    if (this.last === undefined || day > this.last) this.last = day;
  }

  result(): HealthResult {
    const nights = joinNights(this.sleep);
    return {
      records: [...this.water, ...nights.records],
      ignored: this.ignored + nights.dropped,
      bodyMass: this.bodyMass,
      first: this.first,
      last: this.last,
    };
  }
}

const MINUTE = 60_000;
/** Samples less than this apart belong to one episode (PROPOSED, supervisor decision, 2026-11-14). */
const GAP = 60 * MINUTE;
const MAIN_MIN = 60 * MINUTE;
const MAIN_MAX = 18 * 60 * MINUTE;
const NAP_MIN = 5 * MINUTE;
const NAP_MAX = 6 * 60 * MINUTE;

/**
 * Asleep samples joined into episodes: samples that overlap or are less than an hour apart are one
 * episode, from the first falling asleep to the last waking. On each sleep day (the local date of the
 * waking) the longest episode of at least an hour is the night; the others are naps when they fit a
 * nap's bounds (R-SLP-2). An episode that fits neither is dropped and counted.
 */
export function joinNights(samples: readonly SleepSample[]): { records: DeviceRecord[]; dropped: number } {
  const sorted = [...samples].filter((s) => s.end > s.start).sort((a, b) => a.start - b.start);
  const episodes: SleepSample[] = [];
  for (const sample of sorted) {
    const current = episodes[episodes.length - 1];
    if (current !== undefined && sample.start - current.end <= GAP) {
      if (sample.end > current.end) episodes[episodes.length - 1] = { start: current.start, end: sample.end, day: sample.day };
    } else episodes.push(sample);
  }
  const byDay = new Map<string, SleepSample[]>();
  for (const episode of episodes) byDay.set(episode.day, [...(byDay.get(episode.day) ?? []), episode]);
  const records: DeviceRecord[] = [];
  let dropped = 0;
  for (const dayEpisodes of byDay.values()) {
    const length = (e: SleepSample) => e.end - e.start;
    const main = dayEpisodes
      .filter((e) => length(e) >= MAIN_MIN && length(e) <= MAIN_MAX)
      .reduce<SleepSample | undefined>((best, e) => (best === undefined || length(e) > length(best) ? e : best), undefined);
    for (const episode of dayEpisodes) {
      const onset = new Date(episode.start).toISOString();
      const wake = new Date(episode.end).toISOString();
      if (episode === main) records.push({ type: 'sleep_main', onset, wake });
      else if (length(episode) >= NAP_MIN && length(episode) <= NAP_MAX) records.push({ type: 'sleep_nap', onset, wake });
      else dropped++;
    }
  }
  return { records, dropped };
}

/** Reads a stream of export.xml bytes through the scanner, reporting the bytes read; stops when aborted. */
export async function scanHealth(
  stream: ReadableStream<Uint8Array>,
  selected: ReadonlySet<HealthType>,
  onProgress: (bytes: number) => void = () => {},
  signal?: AbortSignal,
): Promise<HealthScanner> {
  const scanner = new HealthScanner(selected);
  const decoder = new TextDecoder();
  const reader = stream.getReader();
  let bytes = 0;
  try {
    for (;;) {
      if (signal?.aborted) throw new DOMException('cancelled', 'AbortError');
      const { done, value } = await reader.read();
      if (done) break;
      bytes += value.byteLength;
      scanner.push(decoder.decode(value, { stream: true }));
      onProgress(bytes);
    }
    scanner.push(decoder.decode());
  } finally {
    reader.releaseLock();
  }
  return scanner;
}
