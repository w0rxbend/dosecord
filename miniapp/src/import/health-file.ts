// Opens a health export the user picked: export.zip as Apple Health writes it, or the export.xml
// inside it. The archive is never read whole (MINI_APP MA-REP-6).
import { scanHealth, type HealthResult, type HealthType } from './health';
import { entryStream, zipEntries, ZipError } from './zip';

/** The largest export the app opens (PROPOSED, supervisor decision, 2026-11-14; MINI_APP MA-SPK-6 sets the real one). */
export const MAX_HEALTH_BYTES = 4 * 1024 * 1024 * 1024;

export type HealthFileError = 'too_large' | 'not_health' | 'unsupported';

export class HealthFileProblem extends Error {
  constructor(readonly reason: HealthFileError) {
    super(reason);
  }
}

const isExportXml = (name: string): boolean => name === 'export.xml' || name.endsWith('/export.xml');

async function xmlStream(file: Blob, name: string): Promise<ReadableStream<Uint8Array>> {
  if (!name.toLowerCase().endsWith('.zip')) return file.stream();
  try {
    const entry = (await zipEntries(file)).find((e) => isExportXml(e.name));
    if (entry === undefined) throw new HealthFileProblem('not_health');
    return await entryStream(file, entry);
  } catch (cause) {
    throw asProblem(cause);
  }
}

/** A ZIP error as the user is told it; any other error as it is. */
function asProblem(cause: unknown): unknown {
  if (!(cause instanceof ZipError)) return cause;
  if (cause.reason === 'not_zip') return new HealthFileProblem('not_health');
  return new HealthFileProblem(cause.reason);
}

/** The records of the selected types, read from `file`; `onProgress` gets the bytes of export.xml read so far. */
export async function readHealthFile(
  file: Blob,
  name: string,
  selected: ReadonlySet<HealthType>,
  onProgress: (bytes: number) => void = () => {},
  signal?: AbortSignal,
): Promise<HealthResult> {
  if (file.size > MAX_HEALTH_BYTES) throw new HealthFileProblem('too_large');
  const stream = await xmlStream(file, name);
  try {
    return (await scanHealth(stream, selected, onProgress, signal)).result();
  } catch (cause) {
    // An entry that decompresses past what it declared stops while it is read.
    throw asProblem(cause);
  }
}
