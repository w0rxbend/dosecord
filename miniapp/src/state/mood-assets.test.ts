import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ApiClient } from '../api/client';
import { setLocale } from '../i18n/index.svelte';
import { moodAssets } from './mood-assets';

function fakeApi() {
  const moodScale = vi.fn(async () => ({ blobRevision: 'r1', statement: 'words of the current language' }));
  const blobStates = vi.fn(async () => ({}));
  return { api: { moodScale, blobStates } as unknown as ApiClient, moodScale };
}

describe('mood assets', () => {
  afterEach(() => setLocale('en'));

  it('are read once per session and language: a language change reads the scale words again', async () => {
    const { api, moodScale } = fakeApi();
    await moodAssets(api);
    await moodAssets(api);
    expect(moodScale).toHaveBeenCalledTimes(1);

    setLocale('uk');
    await moodAssets(api);
    await moodAssets(api);
    expect(moodScale).toHaveBeenCalledTimes(2);
  });
});
