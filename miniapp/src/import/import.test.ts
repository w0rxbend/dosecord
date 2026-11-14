// @vitest-environment node
// ROADMAP G10.5 and G10.7 on the device side: a CSV file goes in batches that never split a record;
// export.xml is read as a stream (a generated file of over 100 MB passes with a bounded buffer);
// only the selected types become records (AC-IMX-9); asleep samples join into nights and naps; a
// ZIP and a ZIP64 archive give back the export.xml inside them.
import { describe, expect, it } from 'vitest';
import { csvBatches, csvRecords, jsonBytes } from './csv-batches';
import { HealthScanner, healthTime, joinNights, scanHealth } from './health';
import { readHealthFile } from './health-file';
import { entryStream, zipEntries, type ZipLimits } from './zip';

const encoder = new TextEncoder();

/** The heap of the Node process the suite runs in (no Node type package in this project). */
declare const process: { memoryUsage(): { heapUsed: number } };

async function deflateRaw(raw: Uint8Array<ArrayBuffer>): Promise<Uint8Array<ArrayBuffer>> {
  const stream = new Blob([raw]).stream().pipeThrough(new CompressionStream('deflate-raw'));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

function record(type: string, start: string, end: string, value: string, unit = ''): string {
  const unitAttr = unit ? ` unit="${unit}"` : '';
  return (
    `<Record type="${type}" sourceName="Watch &gt; of someone" sourceVersion="10"${unitAttr} ` +
    `creationDate="${end}" startDate="${start}" endDate="${end}" value="${value}"/>\n`
  );
}

const SAMPLE =
  '<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE HealthData [\n<!ATTLIST Record type CDATA #REQUIRED>\n]>\n' +
  '<HealthData locale="en_US">\n' +
  record('HKQuantityTypeIdentifierDietaryWater', '2026-11-10 08:00:00 +0200', '2026-11-10 08:00:00 +0200', '250', 'mL') +
  record('HKQuantityTypeIdentifierDietaryWater', '2026-11-10 12:00:00 +0200', '2026-11-10 12:00:00 +0200', '0.5', 'L') +
  record('HKQuantityTypeIdentifierBodyMass', '2026-11-10 07:00:00 +0200', '2026-11-10 07:00:00 +0200', '70', 'kg') +
  record('HKQuantityTypeIdentifierStepCount', '2026-11-10 09:00:00 +0200', '2026-11-10 09:10:00 +0200', '900', 'count') +
  record('HKCategoryTypeIdentifierSleepAnalysis', '2026-11-09 23:00:00 +0200', '2026-11-10 07:00:00 +0200', 'HKCategoryValueSleepAnalysisInBed') +
  record('HKCategoryTypeIdentifierSleepAnalysis', '2026-11-09 23:10:00 +0200', '2026-11-10 02:00:00 +0200', 'HKCategoryValueSleepAnalysisAsleepCore') +
  record('HKCategoryTypeIdentifierSleepAnalysis', '2026-11-10 02:00:00 +0200', '2026-11-10 02:20:00 +0200', 'HKCategoryValueSleepAnalysisAwake') +
  record('HKCategoryTypeIdentifierSleepAnalysis', '2026-11-10 02:20:00 +0200', '2026-11-10 06:40:00 +0200', 'HKCategoryValueSleepAnalysisAsleepDeep') +
  '<Record type="HKCategoryTypeIdentifierSleepAnalysis" sourceName="Phone" startDate="2026-11-10 14:00:00 +0200" ' +
  'endDate="2026-11-10 14:40:00 +0200" value="HKCategoryValueSleepAnalysisAsleepUnspecified">\n' +
  ' <MetadataEntry key="HKTimeZone" value="Europe/Kyiv"/>\n</Record>\n' +
  '</HealthData>\n';

function streamOf(text: string, chunk = 4096): ReadableStream<Uint8Array> {
  const bytes = encoder.encode(text);
  let at = 0;
  return new ReadableStream({
    pull(controller) {
      if (at >= bytes.length) controller.close();
      else {
        controller.enqueue(bytes.slice(at, at + chunk));
        at += chunk;
      }
    },
  });
}

/**
 * A minimal ZIP writer for the tests: deflated or stored entries, optionally with ZIP64 records.
 * `bytes` gives an entry's content as bytes instead of `text`; `declared` writes a size other than the
 * real one into the headers, as a zip bomb does.
 */
async function zip(
  files: readonly { name: string; text?: string; bytes?: Uint8Array<ArrayBuffer>; store?: boolean; declared?: number }[],
  zip64 = false,
): Promise<Uint8Array<ArrayBuffer>> {
  const parts: Uint8Array<ArrayBuffer>[] = [];
  const central: Uint8Array<ArrayBuffer>[] = [];
  let offset = 0;
  for (const file of files) {
    const name = encoder.encode(file.name);
    const raw = file.bytes ?? encoder.encode(file.text ?? '');
    const size = file.declared ?? raw.length;
    const data = file.store ? raw : await deflateRaw(raw);
    const local = new DataView(new ArrayBuffer(30));
    local.setUint32(0, 0x04034b50, true);
    local.setUint16(8, file.store ? 0 : 8, true);
    local.setUint32(18, data.length, true);
    local.setUint32(22, size, true);
    local.setUint16(26, name.length, true);
    parts.push(new Uint8Array(local.buffer), name, data);
    const extra = zip64 ? new DataView(new ArrayBuffer(28)) : new DataView(new ArrayBuffer(0));
    if (zip64) {
      extra.setUint16(0, 0x0001, true);
      extra.setUint16(2, 24, true);
      extra.setBigUint64(4, BigInt(size), true);
      extra.setBigUint64(12, BigInt(data.length), true);
      extra.setBigUint64(20, BigInt(offset), true);
    }
    const entry = new DataView(new ArrayBuffer(46));
    entry.setUint32(0, 0x02014b50, true);
    entry.setUint16(10, file.store ? 0 : 8, true);
    entry.setUint32(20, zip64 ? 0xffffffff : data.length, true);
    entry.setUint32(24, zip64 ? 0xffffffff : size, true);
    entry.setUint16(28, name.length, true);
    entry.setUint16(30, extra.byteLength, true);
    entry.setUint32(42, zip64 ? 0xffffffff : offset, true);
    central.push(new Uint8Array(entry.buffer), name, new Uint8Array(extra.buffer));
    offset += 30 + name.length + data.length;
  }
  const centralSize = central.reduce((n, p) => n + p.length, 0);
  const tail: Uint8Array<ArrayBuffer>[] = [];
  if (zip64) {
    const record = new DataView(new ArrayBuffer(56));
    record.setUint32(0, 0x06064b50, true);
    record.setBigUint64(32, BigInt(files.length), true);
    record.setBigUint64(40, BigInt(centralSize), true);
    record.setBigUint64(48, BigInt(offset), true);
    const locator = new DataView(new ArrayBuffer(20));
    locator.setUint32(0, 0x07064b50, true);
    locator.setBigUint64(8, BigInt(offset + centralSize), true);
    tail.push(new Uint8Array(record.buffer), new Uint8Array(locator.buffer));
  }
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true);
  end.setUint16(10, zip64 ? 0xffff : files.length, true);
  end.setUint32(12, zip64 ? 0xffffffff : centralSize, true);
  end.setUint32(16, zip64 ? 0xffffffff : offset, true);
  tail.push(new Uint8Array(end.buffer));
  const all = [...parts, ...central, ...tail];
  const out = new Uint8Array(all.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const part of all) {
    out.set(part, at);
    at += part.length;
  }
  return out;
}

async function text(stream: ReadableStream<Uint8Array>): Promise<string> {
  return new Response(stream).text();
}

describe('CSV batches', () => {
  it('repeats the header, numbers the first line of each batch and never splits a quoted line break', () => {
    const file = 'occurred_at,meal_type,description\n' + 'a,lunch,"soup\nwith bread"\n' + 'b,snack,\n' + 'c,dinner,x\n';
    expect(csvRecords(file)?.map((r) => r.line)).toEqual([1, 2, 4, 5]);
    expect(csvBatches(file, 2)).toEqual([
      { csv: 'occurred_at,meal_type,description\na,lunch,"soup\nwith bread"\nb,snack,\n', firstLine: 2 },
      { csv: 'occurred_at,meal_type,description\nc,dinner,x\n', firstLine: 5 },
    ]);
  });

  it('keeps each batch under the byte budget of its JSON body, Cyrillic and quotes counted', () => {
    const rows = Array.from({ length: 300 }, (_, i) => `2026-11-10T08:00:00Z,lunch,"суп ""${i}"""\n`).join('');
    const batches = csvBatches(`occurred_at,meal_type,description\r\n${rows}`, 5000, 4000)!;
    expect(batches.length).toBeGreaterThan(1);
    for (const batch of batches) expect(jsonBytes(batch.csv)).toBeLessThanOrEqual(4000);
    expect(batches.map((b) => b.csv.split('\n').length - 2).reduce((a, b) => a + b, 0)).toBe(300);
    expect(jsonBytes('а"\n')).toBe(6);
  });

  it('refuses a file with an open quote, and sends a header alone as one batch', () => {
    expect(csvBatches('a,b\n"x,1\n')).toBeNull();
    expect(csvBatches('occurred_at,amount_ml\r\n')).toEqual([{ csv: 'occurred_at,amount_ml\r\n', firstLine: 2 }]);
  });
});

describe('Apple Health export', () => {
  it('reads the device time with its offset and local date', () => {
    expect(healthTime('2026-11-10 08:00:00 +0200')).toEqual({ iso: '2026-11-10T08:00:00+02:00', day: '2026-11-10' });
    expect(healthTime('yesterday')).toBeNull();
  });

  it('gives drinks, a night and a nap, counts the rest, and ignores body mass apart', async () => {
    const scanner = await scanHealth(streamOf(SAMPLE), new Set(['water', 'sleep']));
    const result = scanner.result();
    expect(result.records).toEqual([
      { type: 'water', at: '2026-11-10T08:00:00+02:00', amountMl: 250 },
      { type: 'water', at: '2026-11-10T12:00:00+02:00', amountMl: 500 },
      { type: 'sleep_main', onset: '2026-11-09T21:10:00.000Z', wake: '2026-11-10T04:40:00.000Z' },
      { type: 'sleep_nap', onset: '2026-11-10T12:00:00.000Z', wake: '2026-11-10T12:40:00.000Z' },
    ]);
    // Body mass, steps, in bed and awake.
    expect(result.ignored).toBe(4);
    expect(result.bodyMass).toBe(1);
    expect([result.first, result.last]).toEqual(['2026-11-10', '2026-11-10']);
  });

  it('only the selected types become records (AC-IMX-9), whatever the chunking', async () => {
    const whole = new HealthScanner(new Set(['sleep']));
    whole.push(SAMPLE);
    for (const size of [1, 7, 61]) {
      const chunked = await scanHealth(streamOf(SAMPLE, size), new Set(['sleep']));
      expect(chunked.result()).toEqual(whole.result());
    }
    expect(whole.result().records.every((r) => r.type !== 'water')).toBe(true);
    expect(whole.result().ignored).toBe(6);
  });

  it('joins asleep samples less than an hour apart; the longest of a day is the night', () => {
    const at = (iso: string) => Date.parse(iso);
    const { records, dropped } = joinNights([
      { start: at('2026-11-09T22:00:00Z'), end: at('2026-11-10T01:00:00Z'), day: '2026-11-10' },
      { start: at('2026-11-10T01:30:00Z'), end: at('2026-11-10T05:00:00Z'), day: '2026-11-10' },
      { start: at('2026-11-10T13:00:00Z'), end: at('2026-11-10T13:02:00Z'), day: '2026-11-10' },
      { start: at('2026-11-10T16:00:00Z'), end: at('2026-11-10T17:00:00Z'), day: '2026-11-10' },
    ]);
    expect(records).toEqual([
      { type: 'sleep_main', onset: '2026-11-09T22:00:00.000Z', wake: '2026-11-10T05:00:00.000Z' },
      { type: 'sleep_nap', onset: '2026-11-10T16:00:00.000Z', wake: '2026-11-10T17:00:00.000Z' },
    ]);
    expect(dropped).toBe(1);
  });

  it('streams a generated export.xml of over 100 MB with a bounded buffer and memory', async () => {
    const steps = record('HKQuantityTypeIdentifierStepCount', '2026-01-01 09:00:00 +0200', '2026-01-01 09:10:00 +0200', '900', 'count');
    const water = record('HKQuantityTypeIdentifierDietaryWater', '2026-01-01 08:00:00 +0200', '2026-01-01 08:00:00 +0200', '250', 'mL');
    const block = steps.repeat(99) + water;
    const blockBytes = encoder.encode(block);
    const blocks = Math.ceil((110 * 1024 * 1024) / blockBytes.length);
    let sent = 0;
    const generated = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('<?xml version="1.0"?>\n<HealthData>\n'));
      },
      pull(controller) {
        if (sent === blocks) {
          controller.enqueue(encoder.encode('</HealthData>\n'));
          controller.close();
        } else {
          controller.enqueue(blockBytes);
          sent++;
        }
      },
    });
    const before = process.memoryUsage().heapUsed;
    let bytes = 0;
    const scanner = await scanHealth(generated, new Set(['water']), (read) => (bytes = read));
    const growth = process.memoryUsage().heapUsed - before;
    expect(bytes).toBeGreaterThan(110 * 1024 * 1024);
    expect(scanner.water.length).toBe(blocks);
    expect(scanner.ignored).toBe(blocks * 99);
    expect(scanner.maxBuffered).toBeLessThan(2 * blockBytes.length);
    // The file is over 100 MB; holding it would need far more than this.
    expect(growth).toBeLessThan(80 * 1024 * 1024);
  }, 120_000);
});

describe('ZIP archives', () => {
  const files = [
    { name: 'apple_health_export/export_cda.xml', text: '<ClinicalDocument/>' },
    { name: 'apple_health_export/export.xml', text: SAMPLE },
    { name: 'apple_health_export/readme.txt', text: 'stored', store: true },
  ];

  it('lists the entries and streams export.xml out of a deflated archive', async () => {
    const archive = new Blob([await zip(files)]);
    const entries = await zipEntries(archive);
    expect(entries.map((e) => e.name)).toEqual(files.map((f) => f.name));
    expect(await text(await entryStream(archive, entries[1]!))).toBe(SAMPLE);
    expect(await text(await entryStream(archive, entries[2]!))).toBe('stored');
  });

  it('reads a ZIP64 archive the same way', async () => {
    const archive = new Blob([await zip(files, true)]);
    const entries = await zipEntries(archive);
    expect(entries[1]?.uncompressedSize).toBe(encoder.encode(SAMPLE).length);
    expect(await text(await entryStream(archive, entries[1]!))).toBe(SAMPLE);
  });

  it('reads the records of export.zip, and says plainly when a file is no health export', async () => {
    const result = await readHealthFile(new Blob([await zip(files)]), 'export.zip', new Set(['water']));
    expect(result.records.filter((r) => r.type === 'water')).toHaveLength(2);
    await expect(readHealthFile(new Blob(['not a zip']), 'export.zip', new Set(['water']))).rejects.toMatchObject({
      reason: 'not_health',
    });
    await expect(
      readHealthFile(new Blob([await zip([files[0]!])]), 'export.zip', new Set(['water'])),
    ).rejects.toMatchObject({ reason: 'not_health' });
  });
});

describe('ZIP limits', () => {
  const zeros = new Uint8Array(32 * 1024 * 1024);
  const small: ZipLimits = { entries: 3, directoryBytes: 4096, totalBytes: 1024 * 1024, ratio: 200 };

  it('stops a zip bomb whose entry decompresses past the size it declared', async () => {
    const bomb = new Blob([await zip([{ name: 'export.xml', bytes: zeros, declared: 1000 }])]);
    const [entry] = await zipEntries(bomb);
    expect(entry?.uncompressedSize).toBe(1000);
    await expect(text(await entryStream(bomb, entry!))).rejects.toMatchObject({ reason: 'too_large' });
    await expect(readHealthFile(bomb, 'export.zip', new Set(['water']))).rejects.toMatchObject({
      reason: 'too_large',
    });
  });

  it('refuses an entry that declares more than the ratio allows, before decompressing it', async () => {
    const honest = new Blob([await zip([{ name: 'export.xml', bytes: zeros }])]);
    await expect(zipEntries(honest)).rejects.toMatchObject({ reason: 'too_large' });
    const entry = { name: 'export.xml', method: 8, compressedSize: 10, uncompressedSize: 10_000, localHeaderOffset: 0 };
    await expect(entryStream(honest, entry)).rejects.toMatchObject({ reason: 'too_large' });
  });

  it('refuses an archive with too many entries or too many decompressed bytes in all', async () => {
    const four = ['a', 'b', 'c', 'd'].map((name) => ({ name, text: 'x' }));
    await expect(zipEntries(new Blob([await zip(four)]), small)).rejects.toMatchObject({ reason: 'too_large' });
    const stored = new Uint8Array(600 * 1024);
    const big = [
      { name: 'a', bytes: stored, store: true },
      { name: 'b', bytes: stored, store: true },
    ];
    await expect(zipEntries(new Blob([await zip(big)]), small)).rejects.toMatchObject({ reason: 'too_large' });
    expect(await zipEntries(new Blob([await zip(big.slice(0, 1))]), small)).toHaveLength(1);
  });
});
