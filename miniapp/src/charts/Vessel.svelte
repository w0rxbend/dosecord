<script lang="ts">
  let { valueMl, goalMl, label }: { valueMl: number; goalMl: number; label: string } = $props();

  const INNER_TOP = 6;
  const INNER_HEIGHT = 84;
  const share = $derived(goalMl > 0 ? Math.min(1, Math.max(0, valueMl / goalMl)) : 0);
  const level = $derived(INNER_HEIGHT * share);
</script>

<!-- Hydration is a vessel, not a ring, on every surface (MINI_APP 5.6). Its label states the
     value, the goal and the percent, so the drawing adds nothing a reader would miss. -->
<svg viewBox="0 0 56 96" width="56" height="96" role="img" aria-label={label}>
  <rect x="8" y={INNER_TOP + INNER_HEIGHT - level} width="40" height={level} rx="4" class="water" />
  <path d="M6 4h44v78a10 10 0 0 1-10 10H16A10 10 0 0 1 6 82z" class="glass" />
  <line x1="6" y1={INNER_TOP} x2="14" y2={INNER_TOP} class="goal" />
</svg>

<style>
  svg {
    flex: none;
  }

  .water {
    fill: var(--dg-domain-hydration);
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
