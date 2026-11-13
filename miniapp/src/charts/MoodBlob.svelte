<script lang="ts">
  import { onDestroy, untrack } from 'svelte';
  import type { BlobAsset, BlobState } from '../api/types';
  import { useApp } from '../app/context';
  import { isReducedMotion } from '../state/motion';
  import { blend, canBlend, findState, mouthPath, NO_ENTRY, outlinePath } from './blob';

  interface Props {
    asset: BlobAsset;
    stateId: string;
    /** The accessible name: the labels in words (MA-MOOD-3). Without one the drawing is decorative. */
    label?: string;
    size?: number;
    breathe?: boolean;
  }

  let { asset, stateId, label, size = 96, breathe = false }: Props = $props();

  const { tg } = useApp();
  const reduced = isReducedMotion(tg);
  const MORPH_MS = 200;
  // One gradient id per instance: several blobs share a page.
  const gradientId = `blob-fill-${Math.random().toString(36).slice(2, 10)}`;

  let scheme = $state(tg.theme().scheme);
  const offTheme = tg.onThemeChanged(() => (scheme = tg.theme().scheme));

  let shown = $state.raw<BlobState | undefined>(undefined);
  let frame = 0;

  // While the slider moves, the outline is blended between the neighbouring states inside one
  // animation frame per step; under reduced motion it switches without interpolation (MA-MOOD-4).
  $effect(() => {
    const target = findState(asset, stateId);
    const from = untrack(() => shown);
    cancelAnimationFrame(frame);
    if (!target || !from || reduced || !canBlend(from, target)) {
      shown = target;
      return;
    }
    const started = performance.now();
    const step = (now: number) => {
      const t = Math.min(1, (now - started) / MORPH_MS);
      shown = t >= 1 ? target : blend(from, target, t);
      if (t < 1) frame = requestAnimationFrame(step);
    };
    frame = requestAnimationFrame(step);
  });

  onDestroy(() => {
    cancelAnimationFrame(frame);
    offTheme();
  });

  const colours = $derived(shown ? (scheme === 'dark' ? shown.dark : shown.light) : undefined);
  // The renderer keeps strokes at least a few pixels wide on a card; a small blob in a list does the
  // same, so its outline and the dots of "no entry" stay visible (one viewBox unit is size / 3 px).
  const unitPx = $derived(size / 3);
  const strokeWidth = $derived(Math.max(shown?.outlineWidth ?? 0, 1.25 / unitPx));
  const dash = $derived(Math.max(0.0133, 2 / unitPx));
</script>

<!-- Inline SVG built by the template from the server's numbers; no markup string (MA-SEC-1). -->
{#if shown && colours}
  <svg
    class="blob"
    class:breathing={breathe && !reduced && shown.id !== NO_ENTRY}
    viewBox="-1.5 -1.35 3 2.7"
    width={size}
    height={size * 0.9}
    role={label ? 'img' : undefined}
    aria-label={label}
    aria-hidden={label ? undefined : 'true'}
    data-state={shown.id}
  >
    {#if shown.gradient.length === 3}
      <defs>
        <radialGradient id={gradientId} gradientUnits="userSpaceOnUse" cx={shown.gradient[0]} cy={shown.gradient[1]} r={shown.gradient[2]}>
          <stop offset="0" stop-color={colours.fillInner} />
          <stop offset="1" stop-color={colours.fillOuter} />
        </radialGradient>
      </defs>
    {/if}
    {#if shown.ground.length === 4}
      <ellipse cx={shown.ground[0]} cy={shown.ground[1]} rx={shown.ground[2]} ry={shown.ground[3]} fill={colours.ground} />
    {/if}
    <g class="body">
      <path
        d={outlinePath(shown.outline)}
        fill={shown.gradient.length === 3 ? `url(#${gradientId})` : colours.fillOuter}
        stroke={colours.outline}
        stroke-width={strokeWidth}
        stroke-dasharray={shown.isDotted ? `${dash} ${dash * 2}` : undefined}
        stroke-linecap="round"
      />
      {#if shown.face}
        {#each shown.face.eyes as eye, index (index)}
          <ellipse cx={eye[0]} cy={eye[1]} rx={eye[2]} ry={eye[3]} fill={shown.face.ink} />
        {/each}
        <path d={mouthPath(shown.face.mouth)} fill="none" stroke={shown.face.ink} stroke-width={shown.face.strokeWidth} stroke-linecap="round" />
        {#each shown.face.brows as brow, index (index)}
          <line x1={brow[0]} y1={brow[1]} x2={brow[2]} y2={brow[3]} stroke={shown.face.ink} stroke-width={shown.face.strokeWidth} stroke-linecap="round" />
        {/each}
      {/if}
    </g>
  </svg>
{/if}

<style>
  .blob {
    display: block;
    flex: none;
    overflow: visible;
  }

  .body {
    transform-origin: 0 0.6px;
  }

  /* The one motion that is not a response to an action: a slow breath, off under reduced motion
     (MA-DS-5, MA-DS-6). Transform only. */
  .breathing .body {
    animation: breathe 4s ease-in-out infinite;
  }

  @keyframes breathe {
    0%,
    100% {
      transform: scale(0.98);
    }
    50% {
      transform: scale(1.02);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .breathing .body {
      animation: none;
    }
  }
</style>
