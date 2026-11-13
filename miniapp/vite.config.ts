import { svelte } from '@sveltejs/vite-plugin-svelte';
import { svelteTesting } from '@testing-library/svelte/vite';
import { defineConfig } from 'vitest/config';

// The application is served under /app/ on the API's own origin (MINI_APP 13.2, ROADMAP G1.13).
export default defineConfig({
  base: '/app/',
  plugins: [svelte(), svelteTesting()],
  build: {
    target: ['chrome100', 'safari15.4'],
    sourcemap: 'hidden',
    assetsInlineLimit: 0,
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.ts'],
    restoreMocks: true,
  },
});
