import { ApiError, toApiError } from '../api/client';

/**
 * One read held in memory with its loading and error state. Data already loaded stays on screen
 * while a refresh runs (MA-STATE-2). An in-repository stand-in for the query library that
 * MINI_APP 12.4 names; nothing is persisted.
 */
export class Resource<T> {
  data = $state.raw<T | undefined>(undefined);
  error = $state.raw<ApiError | undefined>(undefined);
  loading = $state(true);

  private latest = 0;

  constructor(private fetcher: () => Promise<T>) {
    void this.load();
  }

  /** Loads again, optionally from a new source; an answer to an older request is ignored. */
  async load(fetcher: () => Promise<T> = this.fetcher): Promise<void> {
    this.fetcher = fetcher;
    const request = ++this.latest;
    this.loading = true;
    try {
      const data = await fetcher();
      if (request !== this.latest) return;
      this.data = data;
      this.error = undefined;
    } catch (cause) {
      if (request !== this.latest) return;
      this.error = toApiError(cause);
    } finally {
      if (request === this.latest) this.loading = false;
    }
  }

  set(data: T): void {
    this.data = data;
  }
}
