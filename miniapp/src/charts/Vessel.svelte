<script lang="ts">
  interface Props {
    valueMl: number;
    goalMl: number;
    /** States the value, the goal and the percent (MA-HYD-2). */
    label: string;
    size?: number;
  }

  let { valueMl, goalMl, label, size = 56 }: Props = $props();

  const INNER_TOP = 6;
  const INNER_HEIGHT = 84;
  // Nothing is drawn beyond a full vessel (MA-HYD-2).
  const share = $derived(goalMl > 0 ? Math.min(1, Math.max(0, valueMl / goalMl)) : 0);
</script>

<!-- Hydration is a vessel, not a ring, on every surface (MINI_APP 5.6). Its label states the
     value, the goal and the percent, so the drawing adds nothing a reader would miss. The water
     rises by a transform, so the change animates without layout work and cuts under reduced
     motion (MA-DS-5). -->
<svg viewBox="0 0 56 96" width={size} height={(size * 96) / 56} role="img" aria-label={label}>
  <rect x="8" y={INNER_TOP} width="40" height={INNER_HEIGHT} rx="4" class="water" style:transform="scaleY({share})" />
  <path d="M6 4h44v78a10 10 0 0 1-10 10H16A10 10 0 0 1 6 82z" class="glass" />
  <line x1="6" y1={INNER_TOP} x2="14" y2={INNER_TOP} class="goal" />
</svg>

<style>
  svg {
    flex: none;
  }

  .water {
    fill: var(--dg-domain-hydration);
    transform-box: fill-box;
    transform-origin: bottom;
    transition: transform var(--dg-motion-state) ease-out;
  }

  .glass {
    fill: none;
    stroke: var(--dg-text-muted);
    stroke-width: 2;
    stroke-linejoin: round;
  }

  .goal {
    stroke: var(--dg-text);
    stroke-width: 2;
  }
</style>
