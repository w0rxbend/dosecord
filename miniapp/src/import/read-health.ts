// Runs the health export reader off the main thread where a worker exists (MINI_APP MA-REP-6), and
// cuts its records into the batches the server takes.
import type { ImportBatch } from '../api/types';
import type { HealthResult, HealthType } from './health';
import { HealthFileProblem, readHealthFile, type HealthFileError } from './health-file';
import type { HealthMessage, HealthRequest } from './health.worker';
import workerUrl from './health.worker.ts?worker&url';

/** The minimal Trusted Types surface this file uses; the DOM library of TypeScript does not declare it. */
interface ScriptUrlPolicyFactory {
  createPolicy(name: string, rules: { createScriptURL(url: string): string }): { createScriptURL(url: string): unknown };
}

let workerPolicy: { createScriptURL(url: string): unknown } | undefined;

/**
 * The worker's URL as the content security policy requires it (`require-trusted-types-for 'script'`, ARCHITECTURE
 * section 9.8): a worker script URL is a Trusted Types sink. The policy passes the one bundled worker and nothing else.
 */
function trustedWorkerUrl(): string {
  const factory = (globalThis as { trustedTypes?: ScriptUrlPolicyFactory }).trustedTypes;
  if (factory === undefined) return workerUrl;
  workerPolicy ??= factory.createPolicy('dosegram-health-worker', {
    createScriptURL: (url) => {
      if (url !== workerUrl) throw new TypeError('only the health export worker');
      return url;
    },
  });
  // A TrustedScriptURL; the Worker constructor takes it where its type says string.
  return workerPolicy.createScriptURL(workerUrl) as string;
}

/** The server reads at most this many device records per batch (ImportBounds.MaxBatchRecords). */
export const RECORDS_PER_BATCH = 5000;

const HEALTH_ERRORS: readonly HealthFileError[] = ['too_large', 'not_health', 'unsupported'];
const healthErrorOf = (reason: string): HealthFileError => HEALTH_ERRORS.find((known) => known === reason) ?? 'not_health';

export interface HealthReading {
  readonly result: Promise<HealthResult>;
  /** Stops the reading; the result then never settles. */
  cancel(): void;
}

export function readHealth(file: File, selected: readonly HealthType[], onProgress: (bytes: number) => void): HealthReading {
  if (typeof Worker === 'undefined') {
    const controller = new AbortController();
    return {
      result: readHealthFile(file, file.name, new Set(selected), onProgress, controller.signal),
      cancel: () => controller.abort(),
    };
  }
  const worker = new Worker(trustedWorkerUrl(), { type: 'module' });
  const result = new Promise<HealthResult>((resolve, reject) => {
    worker.onmessage = (message: MessageEvent<HealthMessage>) => {
      const data = message.data;
      if (data.kind === 'progress') {
        onProgress(data.bytes);
        return;
      }
      worker.terminate();
      if (data.kind === 'done') resolve(data.result);
      else reject(new HealthFileProblem(healthErrorOf(data.reason)));
    };
  });
  const request: HealthRequest = { file, name: file.name, selected };
  worker.postMessage(request);
  return { result, cancel: () => worker.terminate() };
}

/** The batches of a reading: the first one carries the count of the records left out on the device. */
export function deviceBatches(result: HealthResult): ImportBatch[] {
  const batches: ImportBatch[] = [];
  for (let at = 0; at < result.records.length || batches.length === 0; at += RECORDS_PER_BATCH) {
    batches.push({
      records: result.records.slice(at, at + RECORDS_PER_BATCH),
      ignored: batches.length === 0 ? result.ignored : 0,
      firstIndex: at + 1,
    });
  }
  return batches;
}
