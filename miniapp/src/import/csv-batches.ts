// The batches a Dosegram CSV file is sent in (ROADMAP G10.5; ARCHITECTURE 9.4: a batch is at most 2 MB).
// Each batch repeats the header and names the file line of its first row, so the server's preview
// names lines of the file. A record is never split: a quoted field may hold a line break.

export interface CsvBatch {
  readonly csv: string;
  /** The line of the file the batch's first data row starts on (the header is line 1). */
  readonly firstLine: number;
}

/** The server reads at most this many rows per batch (ImportBounds.MaxBatchRecords). */
export const MAX_BATCH_ROWS = 5000;

/** The JSON body of a batch stays under 2 MB with room for the envelope. */
export const MAX_BATCH_BYTES = 1_800_000;

interface CsvRecord {
  readonly text: string;
  readonly line: number;
}

/** The records of `text` in order, each with its line ending; `null` when a quoted field is not closed. */
export function csvRecords(text: string): CsvRecord[] | null {
  const body = text.startsWith('﻿') ? text.slice(1) : text;
  const records: CsvRecord[] = [];
  let start = 0;
  let line = 1;
  let startLine = 1;
  let quoted = false;
  for (let i = 0; i < body.length; i++) {
    const c = body.charCodeAt(i);
    if (c === 34 /* " */) quoted = !quoted;
    else if (c === 10 /* \n */) {
      line++;
      if (!quoted) {
        records.push({ text: body.slice(start, i + 1), line: startLine });
        start = i + 1;
        startLine = line;
      }
    }
  }
  if (quoted) return null;
  if (start < body.length) records.push({ text: body.slice(start), line: startLine });
  return records;
}

/** The bytes `text` takes as a JSON string: UTF-8, with escapes for quotes, backslashes and control characters. */
export function jsonBytes(text: string): number {
  let bytes = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    if (c === 34 || c === 92 || c === 10 || c === 13 || c === 9) bytes += 2;
    else if (c < 0x20) bytes += 6;
    else if (c < 0x80) bytes += 1;
    else if (c < 0x800) bytes += 2;
    else if (c >= 0xd800 && c <= 0xdbff) {
      bytes += 4;
      i++;
    } else bytes += 3;
  }
  return bytes;
}

/** The batches of a CSV file; `null` when it cannot be read. An empty file or a header alone is one batch. */
export function csvBatches(text: string, maxRows = MAX_BATCH_ROWS, maxBytes = MAX_BATCH_BYTES): CsvBatch[] | null {
  const records = csvRecords(text);
  if (records === null) return null;
  const [header, ...rows] = records;
  if (header === undefined) return [{ csv: '', firstLine: 2 }];
  const headerText = header.text.endsWith('\n') ? header.text : `${header.text}\n`;
  const headerBytes = jsonBytes(headerText);
  const batches: CsvBatch[] = [];
  let parts: string[] = [];
  let bytes = headerBytes;
  let firstLine = rows[0]?.line ?? 2;
  const flush = () => {
    batches.push({ csv: headerText + parts.join(''), firstLine });
    parts = [];
    bytes = headerBytes;
  };
  for (const row of rows) {
    const rowBytes = jsonBytes(row.text);
    if (parts.length > 0 && (parts.length >= maxRows || bytes + rowBytes > maxBytes)) flush();
    if (parts.length === 0) firstLine = row.line;
    parts.push(row.text);
    bytes += rowBytes;
  }
  if (parts.length > 0 || batches.length === 0) flush();
  return batches;
}
