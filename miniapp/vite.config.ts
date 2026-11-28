import { svelte } from '@sveltejs/vite-plugin-svelte';
import { svelteTesting } from '@testing-library/svelte/vite';
import { loadEnv } from 'vite';
import { defineConfig } from 'vitest/config';

// The application is served under /app/ on the API's own origin (MINI_APP 13.2, ROADMAP G1.13).
// `pnpm run dev` against a real backend: VITE_API_PROXY names it (for example http://localhost:8081)
// and /api is proxied there, so the browser still sees one origin (MA-HOST-1).
export default defineConfig(({ mode }) => {
  const apiProxy = loadEnv(mode, '.', 'VITE_').VITE_API_PROXY;
  return {
    base: '/app/',
    plugins: [svelte(), svelteTesting()],
    server: {
      ...(apiProxy ? { proxy: { '/api': apiProxy } } : {}),
      // The renderer's generated stylesheet is the source of the data palette; a test reads it (palette.test.ts).
      fs: { allow: ['.', '../report-renderer/test/resources/goldens'] },
    },
    build: {
      target: ['chrome100', 'safari15.4'],
      sourcemap: 'hidden',
      assetsInlineLimit: 0,
    },
    test: {
      environment: 'jsdom',
      include: ['src/**/*.test.ts'],
      restoreMocks: true,
      // The token stylesheets are read as text by palette.test.ts; every other stylesheet stays empty in tests.
      css: { include: [/tokens\.css/] },
    },
  };
});
