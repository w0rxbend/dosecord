// The health export is read off the main thread (MINI_APP MA-REP-6): the screen stays responsive,
// gets progress at least every second, and cancels by terminating this worker.
import type { HealthType } from './health';
import { HealthFileProblem, readHealthFile } from './health-file';

export interface HealthRequest {
  readonly file: Blob;
  readonly name: string;
  readonly selected: readonly HealthType[];
}

export type HealthMessage =
  | { readonly kind: 'progress'; readonly bytes: number }
  | { readonly kind: 'done'; readonly result: Awaited<ReturnType<typeof readHealthFile>> }
  | { readonly kind: 'failed'; readonly reason: string };

const PROGRESS_MS = 500;

self.onmessage = async (event: MessageEvent<HealthRequest>) => {
  const { file, name, selected } = event.data;
  let reported = 0;
  const post = (message: HealthMessage) => self.postMessage(message);
  try {
    const result = await readHealthFile(file, name, new Set(selected), (bytes) => {
      const now = Date.now();
      if (now - reported >= PROGRESS_MS) {
        reported = now;
        post({ kind: 'progress', bytes });
      }
    });
    post({ kind: 'done', result });
  } catch (cause) {
    post({ kind: 'failed', reason: cause instanceof HealthFileProblem ? cause.reason : 'unreadable' });
  }
};
