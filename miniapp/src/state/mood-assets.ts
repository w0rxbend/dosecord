import type { ApiClient } from '../api/client';
import type { BlobAsset, MoodScale } from '../api/types';
import { locale, type Locale } from '../i18n/index.svelte';

export interface MoodAssets {
  readonly scale: MoodScale;
  readonly asset: BlobAsset;
}

// The scale and the blob geometry hold no user data and are kept for the session (MINI_APP 7.9),
// in memory only (MINI_APP 6.6), once per language: the scale's words come in the account's language.
// A failed read is not kept, so the next screen asks again.
const caches = new WeakMap<ApiClient, Map<Locale, Promise<MoodAssets>>>();

export function moodAssets(api: ApiClient): Promise<MoodAssets> {
  const cache = caches.get(api) ?? new Map<Locale, Promise<MoodAssets>>();
  caches.set(api, cache);
  const language = locale();
  const cached = cache.get(language);
  if (cached) return cached;
  const loading = (async () => {
    const scale = await api.moodScale();
    return { scale, asset: await api.blobStates(scale.blobRevision) };
  })();
  cache.set(language, loading);
  loading.catch(() => cache.delete(language));
  return loading;
}
