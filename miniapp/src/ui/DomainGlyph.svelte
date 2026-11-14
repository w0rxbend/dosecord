<script lang="ts">
  import type { TrackedDomain } from '../api/types';

  interface Props {
    domain: TrackedDomain;
    size?: number;
  }

  let { domain, size = 20 }: Props = $props();
</script>

<!-- The domain icons of VISUALS 3.6, drawn on a 24-unit grid with a 2-unit stroke and round caps:
     cell-split, drop, plate, blob-mini, crescent. Decorative: the domain name is always written
     beside it (VA-PR-06). -->
<svg
  class="glyph"
  data-domain={domain}
  width={size}
  height={size}
  viewBox="0 0 24 24"
  aria-hidden="true"
  focusable="false"
>
  {#if domain === 'medication'}
    <rect x="4" y="4" width="16" height="16" rx="4.5" />
    <line x1="4" y1="12" x2="20" y2="12" />
  {:else if domain === 'hydration'}
    <path d="M12 3.5c3.3 4.1 6 7.4 6 10.6a6 6 0 0 1-12 0c0-3.2 2.7-6.5 6-10.6z" />
  {:else if domain === 'nutrition'}
    <circle cx="12" cy="12" r="8" />
    <path d="M8 12a4 4 0 0 0 8 0" />
  {:else if domain === 'mood'}
    <path
      d="M12 5.2c2.3 0 3.4 1.7 3.4 3.1 2-.3 3.9 1.2 3.5 3.5-.3 1.8-2 2.8-3.2 2.7.2 2.1-1.5 3.8-3.7 3.8s-3.9-1.7-3.7-3.8c-1.3.1-3-.9-3.2-2.7-.4-2.3 1.5-3.8 3.5-3.5 0-1.4 1.1-3.1 3.4-3.1z"
    />
  {:else}
    <path d="M15.6 4.4a8 8 0 1 0 4 11.4 6.6 6.6 0 0 1-4-11.4z" />
  {/if}
</svg>

<style>
  .glyph {
    flex: none;
    fill: none;
    stroke-width: 2;
    stroke-linecap: round;
    stroke-linejoin: round;
  }

  [data-domain='medication'] { stroke: var(--dg-domain-medication); }
  [data-domain='hydration'] { stroke: var(--dg-domain-hydration); }
  [data-domain='nutrition'] { stroke: var(--dg-domain-nutrition); }
  [data-domain='mood'] { stroke: var(--dg-domain-mood); }
  [data-domain='sleep'] { stroke: var(--dg-domain-sleep); }
</style>
