// Reads one file out of a ZIP archive without loading the archive (ROADMAP G10.7, MINI_APP S-14):
// the central directory at the end of the file names each entry and where it starts, and the
// entry's bytes are streamed through DecompressionStream. ZIP64 archives are read too, since a
// health export can pass 4 GB.

export interface ZipEntry {
  readonly name: string;
  readonly method: number;
  readonly compressedSize: number;
  readonly uncompressedSize: number;
  readonly localHeaderOffset: number;
}

export class ZipError extends Error {
  constructor(readonly reason: 'not_zip' | 'unsupported') {
    super(reason);
  }
}

const EOCD = 0x06054b50;
const ZIP64_LOCATOR = 0x07064b50;
const ZIP64_EOCD = 0x06064b50;
const CENTRAL = 0x02014b50;
const LOCAL = 0x04034b50;
const MAX_COMMENT = 0xffff;
const STORED = 0;
const DEFLATE = 8;

async function view(blob: Blob, start: number, end: number): Promise<DataView> {
  const buffer = await blob.slice(start, end).arrayBuffer();
  return new DataView(buffer);
}

const big = (data: DataView, offset: number): number => Number(data.getBigUint64(offset, true));

/** Every entry of the archive's central directory. */
export async function zipEntries(blob: Blob): Promise<ZipEntry[]> {
  const tailStart = Math.max(0, blob.size - (22 + MAX_COMMENT));
  const tail = await view(blob, tailStart, blob.size);
  let eocd = -1;
  for (let i = tail.byteLength - 22; i >= 0; i--) {
    if (tail.getUint32(i, true) === EOCD) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) throw new ZipError('not_zip');
  let count = tail.getUint16(eocd + 10, true);
  let size = tail.getUint32(eocd + 12, true);
  let offset = tail.getUint32(eocd + 16, true);
  if (count === 0xffff || size === 0xffffffff || offset === 0xffffffff) {
    const locator = eocd - 20;
    if (locator < 0 || tail.getUint32(locator, true) !== ZIP64_LOCATOR) throw new ZipError('not_zip');
    const zip64At = big(tail, locator + 8);
    const zip64 = await view(blob, zip64At, zip64At + 56);
    if (zip64.getUint32(0, true) !== ZIP64_EOCD) throw new ZipError('not_zip');
    count = big(zip64, 32);
    size = big(zip64, 40);
    offset = big(zip64, 48);
  }
  const directory = await view(blob, offset, offset + size);
  const decoder = new TextDecoder();
  const entries: ZipEntry[] = [];
  let at = 0;
  for (let n = 0; n < count; n++) {
    if (directory.getUint32(at, true) !== CENTRAL) throw new ZipError('not_zip');
    const nameLength = directory.getUint16(at + 28, true);
    const extraLength = directory.getUint16(at + 30, true);
    const commentLength = directory.getUint16(at + 32, true);
    let compressedSize = directory.getUint32(at + 20, true);
    let uncompressedSize = directory.getUint32(at + 24, true);
    let localHeaderOffset = directory.getUint32(at + 42, true);
    const name = decoder.decode(new Uint8Array(directory.buffer, directory.byteOffset + at + 46, nameLength));
    // ZIP64 extra field: the 8-byte values of the fields set to 0xFFFFFFFF, in this order.
    let extra = at + 46 + nameLength;
    const extraEnd = extra + extraLength;
    while (extra + 4 <= extraEnd) {
      const id = directory.getUint16(extra, true);
      const length = directory.getUint16(extra + 2, true);
      if (id === 0x0001) {
        let field = extra + 4;
        if (uncompressedSize === 0xffffffff) {
          uncompressedSize = big(directory, field);
          field += 8;
        }
        if (compressedSize === 0xffffffff) {
          compressedSize = big(directory, field);
          field += 8;
        }
        if (localHeaderOffset === 0xffffffff) localHeaderOffset = big(directory, field);
      }
      extra += 4 + length;
    }
    entries.push({
      name,
      method: directory.getUint16(at + 10, true),
      compressedSize,
      uncompressedSize,
      localHeaderOffset,
    });
    at = extraEnd + commentLength;
  }
  return entries;
}

/** The decompressed bytes of one entry, as a stream. */
export async function entryStream(blob: Blob, entry: ZipEntry): Promise<ReadableStream<Uint8Array>> {
  const local = await view(blob, entry.localHeaderOffset, entry.localHeaderOffset + 30);
  if (local.getUint32(0, true) !== LOCAL) throw new ZipError('not_zip');
  const start = entry.localHeaderOffset + 30 + local.getUint16(26, true) + local.getUint16(28, true);
  const raw = blob.slice(start, start + entry.compressedSize).stream();
  if (entry.method === STORED) return raw;
  if (entry.method !== DEFLATE || typeof DecompressionStream === 'undefined') throw new ZipError('unsupported');
  return raw.pipeThrough(new DecompressionStream('deflate-raw') as unknown as ReadableWritablePair<Uint8Array, Uint8Array>);
}
