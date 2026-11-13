import type { ApiClient } from '../api/client';
import type { BlobAsset, MoodScale } from '../api/types';

export interface MoodAssets {
  readonly scale: MoodScale;
  readonly asset: BlobAsset;
}

// The scale and the blob geometry hold no user data and are kept for the session (MINI_APP 7.9),
// in memory only (MINI_APP 6.6). A failed read is not kept, so the next screen asks again.
const cache = new WeakMap<ApiClient, Promise<MoodAssets>>();

export function moodAssets(api: ApiClient): Promise<MoodAssets> {
  const cached = cache.get(api);
  if (cached) return cached;
  const loading = (async () => {
    const scale = await api.moodScale();
    return { scale, asset: await api.blobStates(scale.blobRevision) };
  })();
  cache.set(api, loading);
  loading.catch(() => cache.delete(api));
  return loading;
}
